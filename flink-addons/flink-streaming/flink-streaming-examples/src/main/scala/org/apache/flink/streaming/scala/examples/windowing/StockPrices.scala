/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.scala.examples.windowing

import java.util.concurrent.TimeUnit._

import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.scala.windowing.Delta
import org.apache.flink.streaming.api.windowing.helper.Time
import org.apache.flink.util.Collector

import scala.util.Random

object StockPrices {

  case class StockPrice(symbol: String, price: Double)
  case class Count(symbol: String, count: Int)

  val symbols = List("SPX", "FTSE", "DJI", "DJT", "BUX", "DAX", "GOOG")

  val defaultPrice = StockPrice("", 1000)

  def main(args: Array[String]) {

    val env = StreamExecutionEnvironment.getExecutionEnvironment

    //Step 1 
    //Read a stream of stock prices from different sources and merge it into one stream

    //Read from a socket stream at map it to StockPrice objects
    val socketStockStream = env.socketTextStream("localhost", 9999).map(x => {
      val split = x.split(",")
      StockPrice(split(0), split(1).toDouble)
    })

    //Generate other stock streams
    val SPX_Stream = env.addSource(generateStock("SPX")(10) _)
    val FTSE_Stream = env.addSource(generateStock("FTSE")(20) _)
    val DJI_Stream = env.addSource(generateStock("DJI")(30) _)
    val BUX_Stream = env.addSource(generateStock("BUX")(40) _)

    //Merge all stock streams together
    val stockStream = socketStockStream.merge(SPX_Stream, FTSE_Stream, DJI_Stream, BUX_Stream)

    //Step 2
    //Compute some simple statistics on a rolling window
    val windowedStream = stockStream.window(Time.of(10, SECONDS)).every(Time.of(5, SECONDS))

    val lowest = windowedStream.minBy("price").setParallelism(1)
    val maxByStock = windowedStream.groupBy("symbol").maxBy("price")
    val rollingMean = windowedStream.groupBy("symbol").reduceGroup(mean _)

    //Step 3 
    //Use  delta policy to create price change warnings,
    // and also count the number of warning every half minute

    val priceWarnings = stockStream.groupBy("symbol")
      .window(Delta.of(0.05, priceChange, defaultPrice))
      .reduceGroup(sendWarning _)

    val warningsPerStock = priceWarnings.map(Count(_, 1))
      .groupBy("symbol")
      .window(Time.of(30, SECONDS))
      .sum("count")

    //Step 4 
    //Read a stream of tweets and extract the stock symbols

    val tweetStream = env.addSource(generateTweets _)

    val mentionedSymbols = tweetStream.flatMap(
      tweet => for (word <- tweet.split(" ").map(_.toUpperCase())
                    if symbols.contains(word)) yield word)

    val tweetsPerStock = mentionedSymbols.map(Count(_, 1))
      .groupBy("symbol")
      .window(Time.of(30, SECONDS))
      .sum("count")

    //Step 5
    //For advanced analysis we join the number of tweets and
    //the number of price change warnings by stock
    //for the last half minute, we keep only the counts.
    //This information is used to compute rolling correlations
    //between the tweets and the price changes                              

    val tweetsAndWarning = warningsPerStock.join(tweetsPerStock)
      .onWindow(30, SECONDS)
      .where("symbol")
      .equalTo("symbol") { (c1, c2) => (c1.count, c2.count) }

    val rollingCorrelation = tweetsAndWarning.window(Time.of(30, SECONDS))
      .reduceGroup(computeCorrelation _).setParallelism(1)

    rollingCorrelation.print

    env.execute("Stock stream")
  }

  def priceChange(p1: StockPrice, p2: StockPrice): Double = {
    Math.abs(p1.price / p2.price - 1)
  }

  def mean(ts: Iterable[StockPrice], out: Collector[StockPrice]) = {
    if (ts.nonEmpty) {
      out.collect(StockPrice(ts.head.symbol, ts.foldLeft(0: Double)(_ + _.price) / ts.size))
    }
  }

  def sendWarning(ts: Iterable[StockPrice], out: Collector[String]) = {
    if (ts.nonEmpty) out.collect(ts.head.symbol)
  }

  def computeCorrelation(input: Iterable[(Int, Int)], out: Collector[Double]) = {
    if (input.nonEmpty) {
      val var1 = input.map(_._1)
      val mean1 = average(var1)
      val var2 = input.map(_._2)
      val mean2 = average(var2)

      val cov = average(var1.zip(var2).map(xy => (xy._1 - mean1) * (xy._2 - mean2)))
      val d1 = Math.sqrt(average(var1.map(x => Math.pow((x - mean1), 2))))
      val d2 = Math.sqrt(average(var2.map(x => Math.pow((x - mean2), 2))))

      out.collect(cov / (d1 * d2))
    }
  }

  def generateStock(symbol: String)(sigma: Int)(out: Collector[StockPrice]) = {
    var price = 1000.
    while (true) {
      price = price + Random.nextGaussian * sigma
      out.collect(StockPrice(symbol, price))
      Thread.sleep(Random.nextInt(200))
    }
  }

  def average[T](ts: Iterable[T])(implicit num: Numeric[T]) = {
    num.toDouble(ts.sum) / ts.size
  }

  def generateTweets(out: Collector[String]) = {
    while (true) {
      val s = for (i <- 1 to 3) yield (symbols(Random.nextInt(symbols.size)))
      out.collect(s.mkString(" "))
      Thread.sleep(Random.nextInt(500))
    }
  }

}

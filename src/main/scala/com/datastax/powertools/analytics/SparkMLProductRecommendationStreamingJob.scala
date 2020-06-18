package com.datastax.powertools.analytics

import com.datastax.powertools.analytics.ddl.DSECapable
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.ml.recommendation.ALS
import org.apache.spark.sql.functions.split
import org.apache.spark.sql.{DataFrame, SQLContext, SaveMode, SparkSession}
import org.apache.spark.sql.types.{IntegerType, LongType, FloatType}
import org.apache.spark.sql.cassandra._

// For DSE it is not necessary to set connection parameters for spark.master (since it will be done
// automatically)

/**
 * https://github.com/brkyvz/streaming-matrix-factorization
 * https://issues.apache.org/jira/browse/SPARK-6407
 */
object SparkMLProductRecommendationStreamingJob extends DSECapable{

  def main(args: Array[String]) {
    if (args.length < 2) {
      System.err.println("Usage: SparkMLProductRecommendation <hostname> <port>")
      System.exit(1)
    }

    // Create the context with a 1 second batch size
    val sc = connectToDSE("SparkMLProductRecommendation")

    // Set up schema
    setupSchema("recommendations", "predictions", "(user int, item int, preference float, prediction float, PRIMARY KEY((user), item))")

    //Start sql context to read flat file
    val sqlContext = new SQLContext(sc)

    //train with batch file:
    //get the raw data
    val trainingData = sqlContext.read.format("csv")
      .option("header", "true")
      .option("inferSchema", "true")
      .option("delimiter", ":")
      .load("dsefs:///sales_observations")
      .cache()

    //Instantiate our estimator
    val algorithm = new ALS()
      .setMaxIter(5)
      .setRegParam(0.01)
      .setImplicitPrefs(true)
      .setUserCol("user")
      .setItemCol("item")
      .setRatingCol("preference")

    //val algorithm = new LatentMatrixFactorization()
    //train the Estimator against the training data from the file to produce a trained Transformer.
    val model = algorithm.fit(trainingData)
   
    // Subscribe to the product-ratings topic in Kafka and get user ratings as they occur
    // Note that no duplication in storage level only for running locally.
    // Replication necessary in distributed scenario for fault tolerance.

    val spark = SparkSession.builder.appName("SparkMLProductRecommendations").getOrCreate()
    import spark.implicits._

    val inputDF = spark.readStream                                     // Get the DataStreamReader
      .format("kafka")                                                 // Specify the source format as "kafka"
      .option("kafka.bootstrap.servers", (args(0) + ":" + args(1)))    // Configure the Kafka server name and port
      .option("subscribe", "product-ratings")                          // Subscribe to the "en" Kafka topic 
      .option("startingOffsets", "earliest")                           // Rewind stream to beginning when we restart notebook
      .option("maxOffsetsPerTrigger", 1000)                            // Throttle Kafka's processing of the streams
      .load()                                                          // Load the DataFrame
      .select("value").selectExpr("CAST(value AS STRING)").as[(String)]

    val splitDF = inputDF.withColumn("_tmp", split($"value", "\\:")).select(
       $"_tmp".getItem(0).as("user").cast(IntegerType),
       $"_tmp".getItem(1).as("item").cast(IntegerType),
       $"_tmp".getItem(2).as("preference").cast(FloatType)
    )

    val query = splitDF.writeStream.foreachBatch { (batchDF: DataFrame, batchId: Long) =>
       //batchDF.show(10)
       batchDF.write.cassandraFormat("user_ratings", "recommendations").mode(SaveMode.Append).save

       //now predict against the live stream
       //this gives us predicted ratings for the item user combination fed from the stream
       val predictions = model.transform(batchDF).cache();
       //predictions.show(10)
       predictions.write.cassandraFormat("predictions", "recommendations").mode(SaveMode.Append).save
    }.start()
    query.awaitTermination()
  }

  var conf: SparkConf = _
  var sc: SparkContext = _
}

// scalastyle:on println

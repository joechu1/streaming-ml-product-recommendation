# StreamingMLProductRecommendation

This is a guide for how to use the power tools machine learning streaming product recommendation asset brought to you by the Vanguard team.

Upgraded for DataStax Enterprise 6.8 and replaced Netcat to use Apache Kafka instead.

### Motivation

Machine learning powered recommendation engines have wide applications across multiple industries as companies seeking to provide their end customers with deep insights by leveraging data in the moment. Although there are many tools that allow for historical analysis that yield recommendations, DataStax Enterprise (DSE) is particularly well suited to power real-time recommendation / personalization systems. It is when it comes to operationalizing and productionizing analytical systems that DSE will prove most useful. This is largely due to DSEs design objectives of operating at scale, in a distributed fashion, and while fulfilling performance and availability requirements required for user facing, mission critical applications.

### What is included?

This field asset includes a working application for real-time recommendations leveraging the following DSE functionality:

* Machine Learning
* Streaming analytics
* Batch analytics
* Real-time JDBC / SQL (dynamic caching)
* DSEFS

### Business Take Aways

By streaming customer market basket data from a retail organization through DSE analytics and using it to train a Collaborative Filtering Machine Learning model, we are able to maintain a top K list of recommended products by customer that reflect their historical and recent buying patterns.

In the retail industry, both online and brick and mortar businesses are leveraging ML and real-time analytics pipelines to gather insights that become differentiators for them in the marketplace. The DataStax stack is the foundation for enterprise personalization / recommendation systems across multiple industries.

### Technical Take Aways

For a technical deep dive, take a look at the following sections:

- Machine learning model
- Streaming analytics pipeline
- Real-time JDBC / SQL (dynamic caching)

## Startup Script

This Asset leverages
[simple-startup](https://github.com/jshook/simple-startup). To start the entire
asset run `./startup all` for other options run `./startup`


## Requirements:

Best with a m5.xlarge at least. m5.large was not enough memory.

## Automatic Usage:

```
./startup all
```

## Manual Usage:

### Streaming Job:

You can compile and run the example with `./startup streamingJob`

Otherwise...

Build:

    mvn package

and then run the example:

    $ dse spark-submit --deploy-mode cluster --supervise  --class
    com.datastax.powertools.analytics.SparkMLProductRecommendationStreamingJob
    ./target/StreamingMLProductRecommendations-0.2.jar localhost 9092

At the moment, only Spark Shell seems to work, here are my notes for that:

```
dse spark --packages org.apache.spark:spark-sql-kafka-0-10_2.11:2.4.0,org.apache.spark:spark-streaming-kafka-0-10_2.11:2.4.0

mport org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.{SQLContext, SaveMode, SparkSession}
import org.apache.spark.ml.recommendation.ALS
import org.apache.spark.sql.types.{IntegerType, LongType, FloatType}
import org.apache.spark.sql.DataFrame


// Create the context with a 1 second batch size
    val conf = new SparkConf().setAppName("SparkMLProductRecommendation")
    val sc = SparkContext.getOrCreate(conf)

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

    val spark = SparkSession.builder.appName("SparkMLProductRecommendations").getOrCreate()
import spark.implicits._

val inputDF = spark.readStream                                     // Get the DataStreamReader
  .format("kafka")                                                 // Specify the source format as "kafka"
  .option("kafka.bootstrap.servers", "localhost:9092")    // Configure the Kafka server name and port
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
   predictions.show(10)
   predictions.write.cassandraFormat("predictions", "recommendations").mode(SaveMode.Append).save
}.start()
query.awaitTermination()
```

Also, start the Kafka stream with `./kafkastream`

### Docs (Untested)

pull in your submodules

    git submodule update --init
    git submodule sync

then run the server

    hugo server ./content


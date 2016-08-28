package gg.boosted

import gg.boosted.posos.SummonerMatch
import kafka.serializer.{DefaultDecoder, StringDecoder}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.kafka.KafkaUtils
import org.msgpack.core.MessagePack

/**
  * Created by ilan on 8/16/16.
  */
object Utilities {

  def unpackMessage(message: Array[Byte]):String = {
    MessagePack.newDefaultUnpacker(message).unpackString()
  }

  def getKafkaSparkContext(ssc: StreamingContext):DStream[(String, String)] = {

    val kafkaParams = Map[String, String](
      "bootstrap.servers" -> "10.0.0.3:9092",
      "group.id" -> "group1",
      "auto.commit.interval.ms" -> "1000")

    val topics = "boostedgg"

    // Create direct kafka stream with brokers and topics
    val topicsSet = topics.split(",").toSet

    val messages = KafkaUtils.createDirectStream[String, Array[Byte], StringDecoder, DefaultDecoder](
      ssc, kafkaParams, topicsSet)

    val deseredMessages = messages.mapValues(unpackMessage)

    return deseredMessages
  }

  def smRDDToDF(rdd:RDD[SummonerMatch]):DataFrame = {
    import Spark.session.implicits._
    rdd.toDF()
  }

}

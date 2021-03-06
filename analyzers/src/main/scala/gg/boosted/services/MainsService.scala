package gg.boosted.services

import java.time.{LocalDateTime, Period, ZoneId}
import java.util.Date

import gg.boosted.Application
import gg.boosted.configuration.Configuration
import gg.boosted.maps.Items
import gg.boosted.posos.{Mindset, SummonerMatch}
import gg.boosted.utils.GeneralUtils._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.cassandra._
import org.apache.spark.sql.{DataFrame, Dataset, SaveMode}
import org.apache.spark.streaming.dstream.DStream
import org.slf4j.LoggerFactory

/**
  * Created by ilan on 8/26/16.
  */
object MainsService {

    val log = LoggerFactory.getLogger(MainsService.getClass)

    val maxRank = Configuration.getInt("maxrank")

    val minGamesPlayed = Configuration.getInt("min.games.played.with.chrole")

//    def analyze(stream:DStream[SummonerMatch]):Unit = {
//
//        //At this point i'm not sure why i need to work with DStreams at all so:
//        stream.foreachRDD(rdd => {
//            log.info("Processing at: " + new Date()) ;
//            analyze(rdd)
//        })
//    }
//
//    def analyze(rdd:RDD[SummonerMatch]):Unit = {
//        //Convert the rdd to ds so we can use Spark SQL on it
//        if (rdd != null) {
//            val ds = convertToDataSet(rdd)
//            saveFile(ds.toDF(), Configuration.getString("summoner.match.file.location"), SaveMode.Append)
//            analyze(convertToDataSet(rdd))
//        } else {
//            log.debug("RDD is empty")
//        }
//
//    }

    def analyze():Unit = {
        val sc = Application.session.read.cassandraFormat("SUMMONER_MATCHES", "BoostedGG").load()
        sc.show()
    }

    def saveFile(df:DataFrame, fileLocation: String, saveMode: SaveMode = SaveMode.Overwrite):Unit = {
        if (fileLocation != null && fileLocation != "") {
            df.write.format("parquet").mode(saveMode).save(fileLocation)
        }
    }

//    def analyze(ds:Dataset[SummonerMatch]):Unit = {
//        //Get the boosted summoner DF by champion and role
//        log.debug(s"Retrieved ${ds.count()} rows")
//
//        //Populate and populateAndBroadcast items
//        Items.populateAndBroadcast()
//
//        //BoostedSummonersAnalyzer.process(ds, minGamesPlayed, getDateToLookForwardFrom, maxRank)
//
//        val bs = time(BoostedSummonersAnalyzer.findBoostedSummoners(ds, 3, 0, 1000).cache(), "Find boosted summoners")
//
//        saveFile(bs.toDF(), Configuration.getString("boosted.summoners.file.location"))
//
//        log.debug(s"Found ${bs.count()} boosted summoners...")
//
//        val summaries = MatchesAnalyzer.boostedSummonersToSummaries(bs)
//        saveFile(summaries.toDF(), Configuration.getString("summoner.match.summary.location"))
//
////        val summonerMatchSummaryWithWeights = time(MatchesAnalyzer.summariesToWeightedSummaries(summaries).cache(), "BoostedSummnersToWeightMatchSummary")
////        saveFile(summonerMatchSummaryWithWeights, Configuration.getString("weighted.summary.file.location"))
//
//        val clustered = time(CoreItemsClusterer.cluster(summaries), "Cluster")
//        //Mindset.explain(clustered).toDF().write.format("parquet").mode(SaveMode.Overwrite).sortBy("champion", "role").save(Configuration.getString("clustered.file.location"))
//        saveFile(Mindset.explain(clustered).toDF(), Configuration.getString("clustered.file.location"))
//
//    }

    def convertToDataSet(rdd:RDD[SummonerMatch]):Dataset[SummonerMatch] = {
        import Application.session.implicits._
        return Application.session.createDataset(rdd)
    }

    def getDateToLookForwardFrom():Long = {
        //Get the date to start looking from
        val backPeriodInMinutes = Configuration.getLong("window.size.minutes")
        val backPeriodInDays = (backPeriodInMinutes / 60  /24).toInt
        val backPeriod = (LocalDateTime.now().minus(Period.ofDays(backPeriodInDays)))
        val zoneId = ZoneId.of("UTC")
        return backPeriod.atZone(zoneId).toEpochSecond() * 1000
    }

}

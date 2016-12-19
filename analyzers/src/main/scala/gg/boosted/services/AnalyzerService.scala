package gg.boosted.services

import java.util.Date

import com.github.nscala_time.time.Imports._
import gg.boosted.Application
import gg.boosted.analyzers.BoostedSummoner
import gg.boosted.configuration.Configuration
import gg.boosted.posos.SummonerMatch
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Dataset
import org.apache.spark.streaming.dstream.DStream
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

/**
  * Created by ilan on 8/26/16.
  */
object AnalyzerService {

    val log = LoggerFactory.getLogger(AnalyzerService.getClass)

    val maxRank = Configuration.getInt("maxrank")

    val minGamesPlayed = Configuration.getInt("min.games.played")

    def analyze(stream:DStream[SummonerMatch]):Unit = {

        //At this point i'm not sure why i need to work with DStreams at all so:
        stream.foreachRDD(rdd => {
            log.info("Processing at: " + new Date()) ;
            analyze(rdd)
        })
    }

    def analyze(rdd:RDD[SummonerMatch]):Unit = {
        //Convert the rdd to ds so we can use Spark SQL on it
        if (rdd != null) {
            analyze(convertToDataSet(rdd))
        } else {
            log.debug("RDD is empty")
        }

    }

    def analyze(ds:Dataset[SummonerMatch]):Unit = {
        //Get the boosted summoner DF by champion and role
        log.debug(s"Retrieved ${ds.count()} rows")

        val twoWeeks = DateTime.now() - 2.weeks

        BoostedSummoner.process(ds, minGamesPlayed, twoWeeks.getMillis, maxRank)

        //log.info(s"Retrieved total of ${topSummoners.length} boosted summoners")
    }

    def convertToDataSet(rdd:RDD[SummonerMatch]):Dataset[SummonerMatch] = {
        import Application.session.implicits._
        return Application.session.createDataset(rdd)
    }

}

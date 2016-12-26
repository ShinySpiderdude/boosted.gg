package gg.boosted.analyzers

import java.util.Date

import com.datastax.spark.connector._
import gg.boosted.Application.session.implicits._
import gg.boosted.dal.RedisStore
import gg.boosted.maps.Champions
import gg.boosted.posos._
import gg.boosted.riotapi.dtos.`match`.MatchDetail
import gg.boosted.riotapi.{Region, RiotApi}
import gg.boosted.utils.JsonUtil
import gg.boosted.{Application, Role}
import org.apache.spark.sql.Dataset
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer


/**
  * Created by ilan on 8/26/16.
  */
object Analyzer {

    val log = LoggerFactory.getLogger(Analyzer.getClass)

    /**
      *
      * Calculate the Most boosted summoners at each role
      *
      * The calculation should speak for itself, but let's face it, it's kinda hard to read so...
      *
      * The inner subquery
      *
      * @param ds of type [SummonerMatch]
      * @param minGamesPlayed
      * @param since take into account only games played since that time
      * @return
      */
    def process(ds: Dataset[SummonerMatch], minGamesPlayed:Int, since:Long, maxRank:Int):Unit = {

        log.info(s"Processing dataset with ${minGamesPlayed} games played (min), since '${new Date(since)}' with max rank ${maxRank}")

        val boostedSummoners = findBoostedSummoners(ds, minGamesPlayed, since, maxRank).cache()

        log.info(s"Originally i had ${ds.count()} rows and now i have ${boostedSummoners.count()}")

        //Here i download the the full match profile for the matches i haven't stored in redis yet
        val matchedEvents = boostedSummonersToMatchEvents(boostedSummoners)

        //At this point i am at a fix. I need to get the summoner names and lolscore for all summoners that have gotten to this point.
        //The riot api allows me to get names and league entrries for multiple IDs and i need to do it in order to minimize the calls
        //To the riot api. However, i don't think there's a way to call a function for an entire column so i have to use a trick here...
        getNamesAndLoLScore(boostedSummoners)
        import Application.session.implicits._
        val mapWithNames = boostedSummoners.map(
            r=> {
                val summonerId = SummonerId(r.summonerId, Region.valueOf(r.region))
                val summonerName = RedisStore.getSummonerName(summonerId)
                val lolScore = RedisStore.getSummonerLOLScore(summonerId).getOrElse(LoLScore("UNKNOWN","U",0))
                val champion = Champions.byId(r.championId)
                //val lastUpdated = new Date()
                (champion, r.role, summonerName, summonerId.region.toString, r.winrate, r.rank, summonerId.id,
                    lolScore.tier, lolScore.division, lolScore.leaguePoints, lolScore.lolScore, r.gamesPlayed, r.matches)
            })
        //TODO: I should delete from cassandra first, but for now it's ok

        mapWithNames.rdd.saveToCassandra("boostedgg", "boosted_summoners",
            SomeColumns("champion", "role", "summoner_name", "region", "winrate", "rank", "summoner_id",
                "tier", "division", "league_points", "lol_score", "games_played", "matches"))

        log.info(s"Saved ${mapWithNames.count()} rows to cassandra")
    }

    /**
      * Returns a raw representation of boosted summoners.
      * That is, without champion id, lol scores and summoner names
      * @param ds
      * @param minGamesPlayed
      * @param since
      * @param maxRank
      * @return
      */
    def findBoostedSummoners(ds: Dataset[SummonerMatch], minGamesPlayed:Int, since:Long, maxRank:Int)
        :Dataset[BoostedSummoner] = {

        //Use "distinct" so that in case a match got in more than once it will count just once
        import Application.session.implicits._
        ds.distinct().createOrReplaceTempView("MostBoostedSummoners") ;
        ds.sparkSession.sql(
            s"""SELECT championId, roleId, summonerId, region, gamesPlayed, winrate, matches,
               |rank() OVER (PARTITION BY championId, roleId ORDER BY winrate DESC, gamesPlayed DESC, summonerId DESC) as rank FROM (
               |SELECT championId, roleId, summonerId, region, count(*) as gamesPlayed, (sum(if (winner=true,1,0))/count(winner)) as winrate, collect_list(matchId) as matches
               |FROM MostBoostedSummoners
               |WHERE date >= $since
               |GROUP BY championId, roleId, summonerId, region
               |HAVING winrate > 0.5 AND gamesPlayed >= $minGamesPlayed
               |) having rank <= $maxRank
      """.stripMargin)
          //Map from Champion and role ids to their respective names
          .map(r=>BoostedSummoner(r.getInt(0), Role.byId(r.getInt(1)).toString, r.getLong(2),
          r.getString(3), r.getLong(4), r.getDouble(5), r.getSeq[Long](6), r.getInt(7)))
    }


    def boostedSummonersToMatchEvents(ds:Dataset[BoostedSummoner]):Dataset[MatchEvent] = {
        //Download the matches from the dataset
        ds.foreachPartition(partitionOfRecords => {
            var matchIds = new mutable.HashSet[MatchId]

            partitionOfRecords.foreach(row => row.matches.foreach(
                matchh => matchIds += MatchId(matchh, Region.valueOf(row.region))))

            //Get all unknown matches
            val unknownMatches = new mutable.HashSet[MatchId]
            matchIds.foreach(id => RedisStore.getMatch(id).getOrElse(unknownMatches += id))

            unknownMatches.groupBy(_.region).par.foreach(tuple => {
                val region = tuple._1
                val ids = tuple._2
                val api = new RiotApi(region)
                ids.foreach(id => RedisStore.addMatch(id, api.getMatchAsJson(id.id, true)))
            })
        })
        ds.flatMap(row => {
            //The grouping
            val summonerId = row.summonerId
            val region = Region.valueOf(row.region)
            val championId = row.championId
            val role = row.role
            //Get raw event data
            row.matches.flatMap(matchId => {
                val matchDetail = JsonUtil.fromJson[MatchDetail](RedisStore.getMatch(MatchId(matchId, region)).get)
                //Find this guy's participant id
                val participantId =  matchDetail.participantIdentities.asScala.filter(_.player.summonerId == summonerId)(0).participantId
                val winner = matchDetail.participants.asScala.filter(p => p.participantId == participantId)(0).stats.winner
                val gameEvents = matchDetail.timeline.frames.asScala.filter(_.events != null).flatMap(frame => frame.events.asScala)
                val eventsForOurParticipantOnly = gameEvents.filter(
                    event => participantId == Option(event.participantId).getOrElse(event.creatorId)
                )

                //Filter out the events that i, personally, thin aren't relevant
                val relevantEvents = eventsForOurParticipantOnly.filter(event => {
                    List("BUILDING_KILL", "CHAMPION_KILL", "ELITE_MONSTER_KILL", "ITEM_PURCHASED",
                        "ITEM_SOLD", "SKILL_LEVEL_UP", "WARD_KILL", "WARD_PLACED").contains(event.eventType)
                })
                relevantEvents.map(event => {
                    MatchEvent(championId, role, summonerId, region.toString, event.eventType, event.timestamp,
                        event.itemId, event.skillSlot, winner)
                })
            })
        })
    }

    private def getNamesAndLoLScore(ds:Dataset[BoostedSummoner]):Unit = {
        ds.foreachPartition(partitionOfRecords => {

            Champions.populateMapIfEmpty()

            //Run over all the records and get the summonerId field of each one
            val summonerIds = new ListBuffer[SummonerId]
            partitionOfRecords.foreach(row => summonerIds += SummonerId(row.summonerId, Region.valueOf(row.region)))

            //Find names and scores that we don't know yet
            val unknownNames = new ListBuffer[SummonerId]
            val unknownScores = new ListBuffer[SummonerId]

            summonerIds.foreach(id => {
                RedisStore.getSummonerName(id).getOrElse(unknownNames += id)
                RedisStore.getSummonerLOLScore(id).getOrElse(unknownScores += id)
            })

            //group by regions and call their apis
            //TODO: Execute this in parallel for each region and call redis store async
            unknownNames.groupBy(_.region).par.foreach(tuple => {
                val region = tuple._1
                val ids = tuple._2
                val api = new RiotApi(region)
                import collection.JavaConverters._
                api.getSummonerNamesByIds(ids.map(_.id).map(Long.box):_*).asScala.foreach(
                    mapping => RedisStore.addSummonerName(SummonerId(mapping._1, region), mapping._2))
            })

            unknownScores.groupBy(_.region).par.foreach(tuple => {
                val region = tuple._1
                val ids = tuple._2
                val api = new RiotApi(region)

                api.getLeagueEntries(ids.map(_.id).map(Long.box):_*).asScala.foreach(
                    mapping => {
                        val lolScore = LoLScore(mapping._2.tier, mapping._2.division, mapping._2.leaguePoints)
                        RedisStore.addSummonerLOLScore(SummonerId(mapping._1, region), lolScore)
                    })
            })

        })
    }


}
package gg.boosted.analyzers

import gg.boosted.Application
import gg.boosted.posos._
import org.apache.spark.sql.Dataset
import org.slf4j.LoggerFactory



/**
  * Created by ilan on 8/26/16.
  */
object BoostedSummonersAnalyzer {

    val log = LoggerFactory.getLogger(BoostedSummonersAnalyzer.getClass)

//    /**
//      *
//      * Calculate the Most boosted summoners at each role
//      *
//      * The calculation should speak for itself, but let's face it, it's kinda hard to read so...
//      *
//      * The inner subquery
//      *
//      * @param ds    of type [SummonerMatch]
//      * @param minGamesPlayed
//      * @param since take into account only games played since that time
//      * @return
//      */
//    def process(ds: Dataset[SummonerMatch], minGamesPlayed: Int, since: Long, maxRank: Int): Unit = {
//
//        log.info(s"Processing dataset with ${minGamesPlayed} games played (min), since '${new Date(since)}' with max rank ${maxRank}")
//
//        val boostedSummoners = findBoostedSummoners(ds, minGamesPlayed, since, maxRank).cache()
//
//        log.info(s"Originally i had ${ds.count()} rows and now i have ${boostedSummoners.count()}")
//
//        //Here i download the the full match profile for the matches i haven't stored in redis yet
//        val matchedEvents = boostedSummonersToWeightedMatchSummary(boostedSummoners)
//
//        //At this point i am at a fix. I need to get the summoner names and lolscore for all summoners that have gotten to this point.
//        //The riot api allows me to get names and league entrries for multiple IDs and i need to do it in order to minimize the calls
//        //To the riot api. However, i don't think there's a way to call a function for an entire column so i have to use a trick here...
//        getNamesAndLoLScore(boostedSummoners)
//        import Application.session.implicits._
//        val mapWithNames = boostedSummoners.map(
//            r => {
//                val summonerId = SummonerId(r.summonerId, Platform.valueOf(r.region))
//                val summonerName = RedisStore.getSummonerName(summonerId)
//                val lolScore = RedisStore.getSummonerLOLScore(summonerId).getOrElse(LoLScore("UNKNOWN", "U", 0))
//                val champion = Champions.byId(r.championId)
//                //val lastUpdated = new Date()
//                (champion, r.role, summonerName, summonerId.region.toString, r.winrate, r.rank, summonerId.id,
//                    lolScore.tier, lolScore.division, lolScore.leaguePoints, lolScore.lolScore, r.gamesPlayed, r.matches)
//            })
//        //TODO: I should delete from cassandra first, but for now it's ok
//
//        mapWithNames.rdd.saveToCassandra("boostedgg", "boosted_summoners",
//            SomeColum`ns("champion", "role", "summoner_name", "region", "winrate", "rank", "summoner_id",
//                "tier", "division", "league_points", "lol_score", "games_played", "matches"))
//
//        log.info(s"Saved ${mapWithNames.count()} rows to cassandra")
//    }

    /**
      * Returns a raw representation of boosted summoners.
      * That is, without champion id, lol scores and summoner names
      *
      * @param ds
      * @param minGamesPlayed
      * @param since
      * @param maxRank
      * @return
      */
    def findBoostedSummoners(ds: Dataset[SummonerMatch], minGamesPlayed: Int, since: Long, maxRank: Int)
    : Dataset[BoostedSummoner] = {

        //Use "distinct" so that in case a match got in more than once it will count just once
        import Application.session.implicits._
        ds.distinct().createOrReplaceTempView("MostBoostedSummoners")
        ds.sparkSession.sql(
            s"""SELECT championId, roleId, summonerId, region, gamesPlayed, winrate, matches,
               |rank() OVER (PARTITION BY championId, roleId ORDER BY winrate DESC, gamesPlayed DESC, summonerId DESC) as rank FROM (
               |SELECT championId, roleId, summonerId, region, count(*) as gamesPlayed, (sum(if (winner=true,1,0))/count(winner)) as winrate, collect_list(matchId) as matches
               |FROM MostBoostedSummoners
               |WHERE date >= $since
               |GROUP BY championId, roleId, summonerId, region
               |HAVING winrate > 0.5 AND gamesPlayed >= $minGamesPlayed
               |) having rank <= $maxRank
      """.stripMargin).as[BoostedSummoner]
    }


}

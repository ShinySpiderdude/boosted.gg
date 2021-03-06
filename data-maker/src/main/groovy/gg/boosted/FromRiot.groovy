package gg.boosted

import gg.boosted.configuration.Configuration
import gg.boosted.riotapi.Platform
import gg.boosted.riotapi.RiotApi
import gg.boosted.riotapi.dtos.match.Match
import gg.boosted.riotapi.dtos.match.MatchReference
import gg.boosted.stores.CassandraStore
import gg.boosted.stores.RedisStore
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Created by ilan on 8/30/16.
 */
@CompileStatic
class FromRiot {

    static Logger log = LoggerFactory.getLogger(FromRiot)

    static RiotApi riotApi

    static Platform platform

    static void main(String[] args) {
        //RedisStore.reset()

        platform = Platform.EUW1
        riotApi = new RiotApi(platform)

        extract()
    }


    static void extract() {

        //Forget that summoners and matches were ever processed
        //Remove all summoners and matches from redis
        RedisStore.reset()

        //Create an empty set of summonerIds.. This is the queue to which we add new summoners that we find
        //Get an initial seed of summoners
        List<Long> summonerQueue = getInitialSummonerSeed()
        RedisStore.addSummonersToQueue(platform.toString(), summonerQueue as String[])

        //get the time at which we want to look matches from then on
        long gamesPlayedSince = getDateToLookForwardFrom()
        log.info("Looking at games since ${new Date(gamesPlayedSince)}")

        //While the queue is not empty
        String summonerId
        while ((summonerId = RedisStore.popSummonerFromQueue(platform.toString())) != null) {
            //Get the next summoner (it's in summonerId)

            //Check that we haven't seen him yet
            if (RedisStore.wasSummonerProcessedAlready(platform.toString(), summonerId)) {
                log.debug("Summoner ${summonerId} was already processed...")
                continue
            }

            long time = System.currentTimeMillis()

            log.debug("Processing summoner ${summonerId}")

            //In V3 API We need to get an account id for matchlist. for now, i'll make the call. In the future i might cache
            RedisStore.
            Long accountId = riotApi.getSummoner(summonerId as Long).accountId

            //Get his matches since $gamesPlayedSince
            List<MatchReference> matchList = riotApi.getMatchList(accountId, gamesPlayedSince)

            //Determine who this girl mains (if any)
            Set<Tuple2<Integer, String>> mains = getMains(matchList)
            if (log.debugEnabled) {
                StringBuilder sb = new StringBuilder()
                mains.each { main ->

                    sb.append(main).append(" matches(")
                    matchList.each { match ->
                        String role = MatchParser.normalizedRole(match.lane, match.role)
                        if (match.champion == main.first && role == main.second) {
                            sb.append(match.gameId).append(",")
                        }
                    }
                    sb.append("),")
                }
                log.debug("Found ${mains.size()} mains for ${summonerId} (${sb.toString()})")
            }

            //For each match in the summoner's matchlist:
            matchList.each {

                Long gameId = it.gameId

                //Check that we haven't seen this match yet
                if (!RedisStore.wasMatchProcessedAlready(platform.toString(), it.toString())) {

                    log.debug("Processing match ${gameId}")

                    //Get the match itself
                    Match match = riotApi.getMatch(gameId)

                    //create "SummonerMatch" items for each summoner in the match
                    List<SummonerMatch> summonerMatchList = MatchParser.parseMatch(match)

                    //Send them all to the broker
                    //Disregard matches that are shorter than 20 minutes
                    if (match.gameDuration >= 1200) {
                        summonerMatchList.each {
                            //We save a lot of space by saving only the mains to cassandra
                            if (mains.size() > 0 &&
                                    it.summonerId as String == summonerId &&
                                mains.contains(new Tuple2(it.championId, it.role.toString()))) {
                                CassandraStore.saveMatch(it)
                                log.debug("Saved match ${summonerId} to boostedgg.summoner_matches")
                                //KafkaSummonerMatchProducer.send(it)
                            }

                        }
                    }

                    //Add the match to "seen matches"
                    RedisStore.addMatchToProcessedMatches(platform.toString(), it.toString())

                    //Add all the summoners to the summoner queue
                    summonerMatchList.each {RedisStore.addSummonersToQueue(platform.toString(), it.summonerId.toString())}
                } else {
                    log.debug("Match ${it} was already processed...")
                }
            }

            //The summoner is now processed. Add her to the queue
            RedisStore.addSummonersProcessed(platform.toString(), summonerId)

            log.debug("Time taken to process summoner = ${(System.currentTimeMillis() - time) / 1000}S")
        }
    }

    static List<Long> getInitialSummonerSeed() {
        List<Long> seed = []

        //At the beginning of the season there are no challengers and masters, so the following
        //API calls will return null
        seed.addAll(riotApi.getChallengersIds())
        seed.addAll(riotApi.getMastersIds())

        log.debug("Found total {} challengers + masters", seed.size())

        //If there are no challengers or masters (since it's the start of the season)
        //Try to get a seed from some random game
        if (seed.size() == 0) {
            log.info("There are currently no challengers or masters. getting seed from featured games...")
            List<String> summonerNames = []
            new JsonSlurper().parseText(riotApi.getFeaturedGames())["gameList"].each { match ->
                match["participants"].each {participant -> summonerNames += (String)participant["summonerName"]}
            }
            Map<String, Long> namesToIds = [:]
            summonerNames.each {
                namesToIds.put(it, riotApi.getSummonerByName(it).id)

            }
            seed.addAll(namesToIds.values())
        }

        return seed
    }

    static long getDateToLookForwardFrom() {
        //Get the date to start looking from
        Long backPeriodInMinutes = Configuration.getLong("window.size.minutes")
        Integer backPeriodInDays = (backPeriodInMinutes / 60  /24).toInteger()
        LocalDateTime backPeriod = LocalDateTime.now().minusDays(backPeriodInDays)
        ZoneId zoneId = ZoneId.of("UTC")
        return backPeriod.atZone(zoneId).toEpochSecond() * 1000
    }

    static Set<Tuple2<Integer, String>> getMains(List<MatchReference> matchList) {
        Set<Tuple2<Integer, String>> mains = new HashSet<>()

        //The key is champion-role. the value is the number of times it had appeared
        Map<Tuple2<Integer, String>, Integer> mainsCounter = [:]
        matchList.each {
            Tuple2 chrole = new Tuple2(it.champion, MatchParser.normalizedRole(it.lane, it.role))
            int counter = mainsCounter.getOrDefault(chrole, 0)
            counter++
            mainsCounter.put(chrole, counter)
        }

        mainsCounter.each {k, v -> if (v >= Configuration.getInt("min.games.played.with.chrole")) {
            mains.add(k)
        }}
        return mains
    }

}

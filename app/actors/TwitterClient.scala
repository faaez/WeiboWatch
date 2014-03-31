package actors

import akka.actor._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.{Concurrent, Iteratee}
import play.api.libs.ws.{WS,Response}
import play.api.libs.json.{JsValue, Json, JsObject, JsString}
import play.api.libs.oauth.OAuthCalculator

import org.joda.time.DateTime

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.language.postfixOps
import scala.collection.immutable.HashSet

import models.Matches
import utilities.Conf

object TwitterClient {
  val twitterURL = Conf.get("twitter.URL")
  val elasticTweetURL = Conf.get("elastic.TweetURL")
  val elasticPercolatorURL = Conf.get("elastic.PercolatorURL")
  val backOffInterval = 60 * 15 * 1000
  val retryInterval = 20 * 1000

  def now = DateTime.now.getMillis

  /** Protocol for Twitter Client actors */
  case class AddTopic(topic: String)
  case class RemoveTopic(topic: String)
  case object CheckStatus
  case object TweetReceived
  case object Start
  case object BackOff

  /** BirdWatch actor system, supervisor, timer*/
  val system = ActorSystem("BirdWatch")
  val supervisor = system.actorOf(Props(new Supervisor(system.eventStream)), "TweetSupervisor")
  system.scheduler.schedule(60 seconds, 60 seconds, supervisor, CheckStatus )

  /** system-wide channels / enumerators for attaching SSE streams to clients*/
  val (jsonTweetsOut, jsonTweetsChannel) = Concurrent.broadcast[Matches]
  
  /** Subscription topics stored in this MUTABLE collection */
  val topics: scala.collection.mutable.HashSet[String] = new scala.collection.mutable.HashSet[String]()
  
  /** Starts new WS connection to Twitter Streaming API. Twitter disconnects the previous one automatically.
    * Can this be ended explicitly from here though, without resetting the whole underlying clinet? */
  def start() {
    println("Starting client for topics " + topics)
    var url = twitterURL + java.net.URLEncoder.encode(topics.mkString("+").replace(" ", "%20"), "UTF-8")
    // WS.url(url).get(_ => tweetIteratee)
    println(url)
    WS.url(url).get().map { response =>
      (response.json \ "messages") match {
        case JsObject(posts) => {
          posts.map({i => 
            var json = i._2
            WS.url(elasticTweetURL+"_search?q=id:"+(json \ "id").toString().replaceAll("\"","")).get().map { res => 
              var processThis : Boolean = false
              if (res.status == 404) {
                processThis = true
              }
              else if (res.status == 200) {
                val alreadyIndexed = ((res.json \ "hits") \ "total").toString().replaceAll("\"","").toInt
                println(alreadyIndexed)
                if (alreadyIndexed == 0) {
                  processThis = true
                }
              }
              if (processThis) {
                supervisor ! TweetReceived

                val pattern = "[^0-9]".r
                var date : String = (json \ "created_at").toString()
                date = date.substring(date.indexOf(">"))
                date = pattern replaceAllIn(date, "")
                date = date.substring(0,4) + "-" + date.substring(4,6) + "-" + date.substring(6,8) + " " + date.substring(8,10) + ":" + date.substring(10,12) + " CST"
                var text : String = (json \ "text").toString()
                val div_pattern = "<div[^>]*?>.*?</div>".r
                val a_pattern = "<a[^>]*?>.*?</a>".r
                val img_pattern = "<img[^>]*?/>".r
                val span_begin_pattern = "<span[^>]*?>".r
                val span_end_pattern = "</span>".r
                text = span_begin_pattern replaceAllIn(text, "")
                text = span_end_pattern replaceAllIn(text, "")
                text = div_pattern replaceAllIn(text, "")
                text = a_pattern replaceAllIn(text, "")
                text = img_pattern replaceAllIn(text, "")
                

                var weibopost : JsObject = Json.obj(
                    "text" -> text,
                    "datetime" -> date,
                    "id" -> json \ "id",
                    "status_id" -> json \ "status_id",
                    "profile_image_url" -> json \ "profile_image_url",
                    "reposts_count" -> json \ "reposts_count",
                    "hotness" -> json \ "hotness",
                    "user_followers_count" -> json \ "user_followers_count",
                    "user_id" -> json \ "user_id",
                    "user_name" -> json \ "user_name",
                    "censored" -> json \ "censored",
                    "deleted" -> json \ "deleted",
                    "order_by_value" -> json \ "order_by_value"
                    )

                (weibopost \ "id").asOpt[String].map { id => WS.url(elasticTweetURL + id).put(weibopost) }
                matchAndPush(weibopost)
            }
            }
          })
        }
        case _ => println("received something else")
      }
      
    }
    // WS.url(url).withRequestTimeout(-1).sign(OAuthCalculator(Conf.consumerKey, Conf.accessToken)).get(_ => tweetIteratee)
  }

  /** Actor taking care of monitoring the WS connection */
  class Supervisor(eventStream: akka.event.EventStream) extends Actor {
    var lastTweetReceived = 0L 
    var lastBackOff = 0L

    /** Receives control messages for starting / restarting supervised client and adding or removing topics */
    def receive = {
      case AddTopic(topic)  => topics.add(topic)
      case RemoveTopic(topic) => topics.remove(topic)
      case Start => start()
      case CheckStatus => if (now - lastTweetReceived > retryInterval && now - lastBackOff > backOffInterval) start()
      case BackOff => lastBackOff = now  
      case TweetReceived => lastTweetReceived = now   
    }
  }

  /** Takes JSON and matches it with percolation queries in ElasticSearch
    * @param json JsValue to match against 
    */
  def matchAndPush(json: JsValue) {
    WS.url(elasticPercolatorURL).post(Json.obj("doc" -> json)).map {
      res => (Json.parse(res.body) \ "matches").asOpt[List[JsValue]].map {
        matches => {
          val items = matches.map { m => (m \ "_id").as[String] }
          jsonTweetsChannel.push(Matches(json, HashSet.empty[String] ++ items))
        }
      }
    }
  }
}

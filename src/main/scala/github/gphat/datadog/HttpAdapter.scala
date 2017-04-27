package github.gphat.datadog

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import grizzled.slf4j.Logging
import spray.can.Http
import spray.http.Uri._
import spray.http._
import spray.httpx.RequestBuilding._

import scala.concurrent.Future
import scala.util.{Failure, Success}

class HttpAdapter(
  httpTimeoutSeconds: Int = 10,
  actorSystem: Option[ActorSystem] = None
  ) extends Logging {

  // If we didn't get an actor system passed in
  implicit val finalAS = actorSystem.getOrElse(ActorSystem())
  import finalAS.dispatcher // execution context for futures
  // Akka's Ask pattern requires an implicit timeout to know
  // how long to wait for a response.
  implicit val timeout = Timeout(httpTimeoutSeconds, TimeUnit.SECONDS)

  def doRequest(
    scheme: String,
    authority: String,
    path: String,
    method: String,
    body: Option[String] = None,
    params: Map[String,Option[String]] = Map.empty,
    contentType: String = "json"): Future[Response] = {

    // Turn a map of string,opt[string] into a map of string,string which is
    // what Query wants
    val filteredParams = params.filter(
      // Filter out keys that are None
      _._2.isDefined
    ).map(
      // Convert the remaining tuples to str,str
      param => (param._1 -> param._2.get)
    )
    // Make a Uri
    val finalUrl = Uri(
      scheme = scheme,
      authority = Authority(host = Host(authority)),
      path = Path("/api/v1/" + path),
      query = Query(filteredParams)
    )

    // Use the provided case classes from spray-client
    // to construct an HTTP request of the type needed.
    val httpRequest: HttpRequest = method match {
      case "DELETE" => Delete(finalUrl, body)
      case "GET" => Get(finalUrl, body)
      case "POST" => contentType match {
        case "json" => Post(finalUrl, body.map({
          b => HttpEntity(ContentTypes.`application/json`, b)
        }).getOrElse(HttpEntity.Empty))
        case _ => {
          // This is going to be a form-encoded post. There's only one
          // API call that works this way (ugh) so I'm not going to worry
          // too much about making this work as cleanly as the rest of the
          // stuff. (IMO)
          val formUrl = Uri(
            scheme = scheme,
            authority = Authority(host = Host(authority)),
            path = Path("/api/v1/" + path)
          )

          Post(formUrl, FormData(filteredParams))
        }
      }
      case "PUT" => Put(finalUrl, HttpEntity(ContentTypes.`application/json`, body.get))
      case _ => throw new IllegalArgumentException("Unknown HTTP method: " + method)
    }

    debug("%s: %s".format(method, finalUrl))
    // For spelunkers, the ? is a function of the Akka "ask pattern". Unlike !
    // it waits for a response in the form of a future. In this case we're
    // sending along a case class representing the type of HTTP request we want
    // to do and something down in the guts of the actors handles it and gets
    // us a response.
    doHttp(httpRequest)
  }

  def doHttp(request: HttpRequest): Future[Response] = {
    (IO(Http) ? request).mapTo[HttpResponse].map({ res =>
      Response(statusCode = res.status.intValue, res.entity.asString, res.headers.toString)
    })
  }

  def shutdown = {
    (IO(Http) ? Http.CloseAll) onComplete {
      // When this completes we will shutdown the actor system if it wasn't
      // supplied by the user.
      case Success(x) => if (actorSystem.isEmpty) { finalAS.shutdown() }
      // If we fail to close not sure what we can except rethrow
      case Failure(t) => throw t
    }
  }
}

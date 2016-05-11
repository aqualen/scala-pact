package com.itv.scalapact.plugin.stubber

import com.itv.scalapact.plugin.common.Arguments
import com.itv.scalapactcore._
import org.http4s.dsl.{->, /, Root, _}
import org.http4s.server.blaze.BlazeBuilder
import org.http4s.util.CaseInsensitiveString
import org.http4s.{HttpService, Request, Response, Status}
import com.itv.scalapact.plugin.common.ColourOuput._

import scalaz.{-\/, \/-}

object PactStubService {

  lazy val startServer: Arguments => Unit = config => {
    println(("Starting ScalaPact Stubber on: http://" + config.giveHost + ":" + config.givePort).white.bold)

    BlazeBuilder.bindHttp(config.givePort, config.giveHost)
      .mountService(PactStubService.service, "/")
      .run
      .awaitShutdown()
  }

  private val isAdminCall: Request => Boolean = request =>
    request.pathInfo.startsWith("/interactions") &&
      request.headers.get(CaseInsensitiveString("X-Pact-Admin")).exists(h => h.value == "true")

  private val service = HttpService {

    case req @ GET -> Root / path =>
      matchRequestWithResponse(req)

    case req @ PUT -> Root / path =>
      matchRequestWithResponse(req)

    case req @ POST -> Root / path =>
      matchRequestWithResponse(req)

    case req @ DELETE -> Root / path =>
      matchRequestWithResponse(req)

  }

  private def matchRequestWithResponse(req: Request): scalaz.concurrent.Task[Response] = {
    if(isAdminCall(req)) {

      req.method.name.toUpperCase match {
        case m if m == "GET" =>
          val output = ScalaPactWriter.pactToJsonString(Pact(PactActor(""), PactActor(""), InteractionManager.getInteractions))
          Ok(output)

        case m if m == "POST" || m == "PUT" =>
          ScalaPactReader.jsonStringToPact(req.bodyAsText.runLast.run.getOrElse("")) match {
            case \/-(r) =>
              InteractionManager.addInteractions(r.interactions)

              val output = ScalaPactWriter.pactToJsonString(Pact(PactActor(""), PactActor(""), InteractionManager.getInteractions))
              Ok(output)

            case -\/(l) =>
              InternalServerError(l)
          }

        case m if m == "DELETE" =>
          InteractionManager.clearInteractions()

          val output = ScalaPactWriter.pactToJsonString(Pact(PactActor(""), PactActor(""), InteractionManager.getInteractions))
          Ok(output)
      }

    }
    else {

      import HeaderImplicitConversions._

      InteractionManager.findMatchingInteraction(
        InteractionRequest(
          method = Option(req.method.name.toUpperCase),
          headers = Option(req.headers),
          query = if(req.params.isEmpty) None else Option(req.params.toList.map(p => p._1 + "=" + p._2).mkString("&")),
          path = Option(req.pathInfo),
          body = req.bodyAsText.runLast.run,
          matchingRules = None
        )
      ) match {
        case \/-(ir) =>
          Status.fromInt(ir.response.status.getOrElse(200)) match {
            case \/-(code) =>
              Http4sRequestResponseFactory.buildResponse(
                status = code,
                headers = ir.response.headers.getOrElse(Map.empty),
                body = ir.response.body
              )

            case -\/(l) => InternalServerError(l.sanitized)
          }

        case -\/(message) =>
          NotFound(message)
      }

    }
  }
}

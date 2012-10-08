package docs

import org.specs2.mutable.Specification
import cc.spray.testkit.Specs2RouteTest
import cc.spray.routing.{Route, HttpService}
import akka.actor.Actor


class ExceptionHandlerExamplesSpec extends Specification with Specs2RouteTest with HttpService {
  implicit def actorRefFactory = system

  //# example-1
  import cc.spray.routing.ExceptionHandler
  import cc.spray.http.StatusCodes._

  implicit val myExceptionHandler = ExceptionHandler.fromPF {
    case e: ArithmeticException => log => ctx =>
      log.warning("Request {} could not be handled normally", ctx.request)
      ctx.complete(InternalServerError, "Bad numbers, bad result!!!")
  }

  class MyService extends Actor with HttpService {
    def actorRefFactory = context

    def receive = runRoute {
      `<my-route-definition>`
    }
  }
  //#

  def `<my-route-definition>`: Route = null

  "example" in {
    Get() ~> sealRoute(completeLater(ctx => 1 / 0)) ~> check {
      entityAs[String] === "Bad numbers, bad result!!!"
    }
  }

}

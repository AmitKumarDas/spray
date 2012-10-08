/*
 * Copyright (C) 2011-2012 spray.cc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray.routing

import collection.GenTraversableOnce
import akka.dispatch.Future
import akka.actor.{Status, ActorRef}
import akka.spray.UnregisteredActorRef
import cc.spray.httpx.marshalling.{MarshallingContext, Marshaller}
import cc.spray.util._
import cc.spray.http._
import StatusCodes._
import HttpHeaders._
import MediaTypes._


/**
 * Immutable object encapsulating the context of an [[cc.spray.http.HttpRequest]]
 * as it flows through a ''spray'' Route structure.
 */
case class RequestContext(
  request: HttpRequest,
  responder: ActorRef,
  unmatchedPath: String = ""
) {

  /**
   * Returns a copy of this context with the HttpRequest transformed by the given function.
   */
  def mapRequest(f: HttpRequest => HttpRequest): RequestContext = {
    val transformed = f(request)
    if (transformed eq request) this else copy(request = transformed)
  }

  /**
   * Returns a copy of this context with the responder transformed by the given function.
   */
  def mapResponder(f: ActorRef => ActorRef) = {
    val transformed = f(responder)
    if (transformed eq responder) this else copy(responder = transformed)
  }

  /**
   * Returns a copy of this context with the given response transformation function chained into the response chain.
   */
  def mapRouteResponse(f: Any => Any) = mapResponder { previousResponder =>
    new UnregisteredActorRef(responder) {
      def handle(message: Any)(implicit sender: ActorRef) {
        previousResponder ! f(message)
      }
    }
  }

  /**
   * Returns a copy of this context with the given response transformation function chained into the response chain.
   */
  def flatMapRouteResponse(f: Any => Seq[Any]) = mapResponder { previousResponder =>
    new UnregisteredActorRef(responder) {
      def handle(message: Any)(implicit sender: ActorRef) {
        f(message).foreach(previousResponder ! _)
      }
    }
  }

  /**
   * Returns a copy of this context with the given response transformation function chained into the response chain.
   */
  def mapRouteResponsePF(f: PartialFunction[Any, Any]) = mapRouteResponse { message =>
    if (f.isDefinedAt(message)) f(message) else message
  }

  /**
   * Returns a copy of this context with the given response transformation function chained into the response chain.
   */
  def flatMapRouteResponsePF(f: PartialFunction[Any, Seq[Any]]) = flatMapRouteResponse { message =>
    if (f.isDefinedAt(message)) f(message) else message :: Nil
  }

  /**
   * Returns a copy of this context with the given response transformation function chained into the response chain.
   */
  def mapHttpResponse(f: HttpResponse => HttpResponse) = mapRouteResponse {
    case x: HttpResponse => f(x)
    case ChunkedResponseStart(x) => ChunkedResponseStart(f(x))
    case x => x
  }

  /**
   * Returns a copy of this context with the given response transformation function chained into the response chain.
   */
  def mapHttpResponseEntity(f: HttpEntity => HttpEntity) =
    mapHttpResponse(_.mapEntity(f))

  /**
   * Returns a copy of this context with the given response transformation function chained into the response chain.
   */
  def mapHttpResponseHeaders(f: List[HttpHeader] => List[HttpHeader]) =
    mapHttpResponse(_.mapHeaders(f))

  /**
   * Returns a copy of this context with the given rejection transformation function chained into the response chain.
   */
  def mapRejections(f: List[Rejection] => List[Rejection]) = mapRouteResponse {
    case Rejected(rejections) => Rejected(f(rejections))
    case x => x
  }

  /**
   * Returns a copy of this context with the unmatchedPath transformed by the given function.
   */
  def mapUnmatchedPath(f: String => String) = {
    val transformed = f(unmatchedPath)
    if (transformed == unmatchedPath) this else copy(unmatchedPath = transformed)
  }

  /**
   * Returns a copy of this context with the given function handling a part of the response space.
   */
  def withRouteResponseHandling(f: PartialFunction[Any, Unit]) = mapResponder { previousResponder =>
    new UnregisteredActorRef(responder) {
      def handle(message: Any)(implicit sender: ActorRef) {
        if (f.isDefinedAt(message)) f(message) else previousResponder ! message
      }
    }
  }

  /**
   * Returns a copy of this context with the given rejection handling function chained into the response chain.
   */
  def withRejectionHandling(f: List[Rejection] => Unit) = mapResponder { previousResponder =>
    new UnregisteredActorRef(responder) {
      def handle(message: Any)(implicit sender: ActorRef) {
        message match {
          case Rejected(rejections) => f(rejections)
          case x => previousResponder ! x
        }
      }
    }
  }

  /**
   * Returns a copy of this context that automatically sets the sender of all messages to its responder to the given
   * one, if no explicit sender is passed along from upstream.
   */
  def withDefaultSender(defaultSender: ActorRef) = copy(
    responder = new UnregisteredActorRef(responder) {
      def handle(message: Any)(implicit sender: ActorRef) {
        responder.tell(message, if (sender == null) defaultSender else sender)
      }
    }
  )

  /**
   * Rejects the request with the given rejections.
   */
  def reject(rejections: Rejection*): RequestResult = {
    responder ! Rejected(rejections.toList)
    RequestResult.NotCompletedHere
  }

  /**
   * Completes the request with redirection response of the given type to the given URI.
   * The default redirectionType is a temporary `302 Found`.
   */
  def redirect(uri: String, redirectionType: Redirection = Found) = {
    complete {
      HttpResponse(
        status = redirectionType,
        headers = Location(uri) :: Nil,
        entity = redirectionType.htmlTemplate.toOption.map(s => HttpBody(`text/html`, s format uri))
      )
    }
  }

  /**
   * Completes the request with status "200 Ok" and the response entity created by marshalling the given object using
   * the in-scope marshaller for the type.
   */
  def complete[T :Marshaller](obj: T): RequestResult = {
    complete(OK, obj)
  }

  /**
   * Completes the request with the given status and the response entity created by marshalling the given object using
   * the in-scope marshaller for the type.
   */
  def complete[T :Marshaller](status: StatusCode, obj: T): RequestResult = {
    complete(status, Nil, obj)
  }

  /**
   * Completes the request with the given status, headers and the response entity created by marshalling the
   * given object using the in-scope marshaller for the type.
   */
  def complete[T](status: StatusCode, headers: List[HttpHeader], obj: T)(implicit marshaller: Marshaller[T]): RequestResult = {
    marshaller(obj, marshallingContext(status, headers))
    RequestResult.NotCompletedHere
  }

  /**
   * Completes the request with the given [[cc.spray.http.HttpResponse]].
   */
  def complete(response: HttpResponse): RequestResult = {
    responder ! response
    RequestResult.NotCompletedHere
  }

  /**
   * Bubbles the given error up the response chain, where it is dealt with by the closest `handleExceptions`
   * directive and its ExceptionHandler.
   */
  def failWith(error: Throwable): RequestResult = {
    responder ! Status.Failure(error)
    RequestResult.NotCompletedHere
  }

  /**
   * Creates a MarshallingContext using the given status code and response headers.
   */
  def marshallingContext(status: StatusCode, headers: List[HttpHeader]): MarshallingContext =
    new MarshallingContext {
      def tryAccept(contentType: ContentType) = request.acceptableContentType(contentType)
      def rejectMarshalling(onlyTo: Seq[ContentType]) { reject(UnacceptedResponseContentTypeRejection(onlyTo)) }
      def marshalTo(entity: HttpEntity) { complete(response(entity)) }
      def handleError(error: Throwable) { failWith(error) }
      def startChunkedMessage(entity: HttpEntity)(implicit sender: ActorRef) = {
        responder.tell(ChunkedResponseStart(response(entity)), sender)
        responder
      }
      def response(entity: HttpEntity) = HttpResponse(status, entity, headers)
    }
}

case class Rejected(rejections: List[Rejection]) {
  def map(f: Rejection => Rejection) = Rejected(rejections.map(f))
  def flatMap(f: Rejection => GenTraversableOnce[Rejection]) = Rejected(rejections.flatMap(f))
}
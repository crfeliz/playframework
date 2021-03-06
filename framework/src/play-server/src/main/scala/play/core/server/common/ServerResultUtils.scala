/*
 * Copyright (C) 2009-2016 Lightbend Inc. <https://www.lightbend.com>
 */
package play.core.server.common

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import play.api.Logger
import play.api.mvc._
import play.api.http._
import play.api.http.HeaderNames._
import play.api.http.Status._
import scala.concurrent.Future
import scala.util.control.NonFatal

object ServerResultUtils {

  private val logger = Logger(ServerResultUtils.getClass)

  /**
   * Determine whether the connection should be closed, and what header, if any, should be added to the response.
   */
  def determineConnectionHeader(request: RequestHeader, result: Result): ConnectionHeader = {
    if (request.version == HttpProtocol.HTTP_1_1) {
      if (result.header.headers.get(CONNECTION).exists(_.equalsIgnoreCase(CLOSE))) {
        // Close connection, header already exists
        DefaultClose
      } else if ((result.body.isInstanceOf[HttpEntity.Streamed] && result.body.contentLength.isEmpty)
        || request.headers.get(CONNECTION).exists(_.equalsIgnoreCase(CLOSE))) {
        // We need to close the connection and set the header
        SendClose
      } else {
        DefaultKeepAlive
      }
    } else {
      if (result.header.headers.get(CONNECTION).exists(_.equalsIgnoreCase(CLOSE))) {
        DefaultClose
      } else if ((result.body.isInstanceOf[HttpEntity.Streamed] && result.body.contentLength.isEmpty) ||
        request.headers.get(CONNECTION).forall(!_.equalsIgnoreCase(KEEP_ALIVE))) {
        DefaultClose
      } else {
        SendKeepAlive
      }
    }
  }

  /**
   * Validate the result.
   *
   * Returns the validated result, which may be an error result if validation failed.
   */
  def validateResult(request: RequestHeader, result: Result, httpErrorHandler: HttpErrorHandler)(implicit mat: Materializer): Future[Result] = {
    if (request.version == HttpProtocol.HTTP_1_0 && result.body.isInstanceOf[HttpEntity.Chunked]) {
      cancelEntity(result.body)
      val exception = new ServerResultException("HTTP 1.0 client does not support chunked response", result, null)
      val errorResult: Future[Result] = httpErrorHandler.onServerError(request, exception)
      import play.core.Execution.Implicits.trampoline
      errorResult.map { originalErrorResult: Result =>
        // Update the original error with a new status code and a "Connection: close" header
        import originalErrorResult.{ header => h }
        val newHeader = h.copy(
          status = Status.HTTP_VERSION_NOT_SUPPORTED,
          headers = h.headers + (CONNECTION -> CLOSE)
        )
        originalErrorResult.copy(header = newHeader)
      }
    } else if (!mayHaveEntity(result.header.status) && !result.body.isKnownEmpty) {
      cancelEntity(result.body)
      Future.successful(result.copy(body = HttpEntity.Strict(ByteString.empty, result.body.contentType)))
    } else {
      Future.successful(result)
    }
  }

  /**
   * Handles result conversion in a safe way.
   *
   * 1. Tries to convert the `Result`.
   * 2. If there's an error, calls the `HttpErrorHandler` to get a new
   *    `Result`, then converts that.
   * 3. If there's an error with *that* `Result`, uses the
   *    `DefaultHttpErrorHandler` to get another `Result`, then converts
   *    that.
   * 4. Hopefully there are no more errors. :)
   * 5. If calling an `HttpErrorHandler` throws an exception, then a
   *    fallback response is returned, without an conversion.
   */
  def resultConversionWithErrorHandling[R](
    requestHeader: RequestHeader,
    result: Result,
    errorHandler: HttpErrorHandler)(resultConverter: Result => Future[R])(fallbackResponse: => R): Future[R] = {

    import play.core.Execution.Implicits.trampoline

    def handleConversionError(conversionError: Throwable): Future[R] = {
      try {
        // Log some information about the error
        if (logger.isErrorEnabled) {
          val prettyHeaders = result.header.headers.map { case (name, value) => s"<$name>: <$value>" }.mkString("[", ", ", "]")
          val msg = s"Exception occurred while converting Result with headers $prettyHeaders. Calling HttpErrorHandler to get alternative Result."
          logger.error(msg, conversionError)
        }

        // Call the HttpErrorHandler to generate an alternative error
        errorHandler.onServerError(
          requestHeader,
          new ServerResultException("Error converting Play Result for server backend", result, conversionError)
        ).flatMap { errorResult =>
            // Convert errorResult using normal conversion logic. This time use
            // the DefaultErrorHandler if there are any problems, e.g. if the
            // current HttpErrorHandler returns an invalid Result.
            resultConversionWithErrorHandling(requestHeader, errorResult, DefaultHttpErrorHandler)(resultConverter)(fallbackResponse)
          }
      } catch {
        case NonFatal(onErrorError) =>
          // Conservatively handle exceptions thrown by HttpErrorHandlers by
          // returning a fallback response.
          logger.error("Error occurred during error handling. Original error: ", conversionError)
          logger.error("Error occurred during error handling. Error handling error: ", onErrorError)
          Future.successful(fallbackResponse)
      }
    }

    try {
      // Try to convert the result
      resultConverter(result).recoverWith { case t => handleConversionError(t) }
    } catch {
      case NonFatal(e) => handleConversionError(e)
    }

  }

  /** Whether the given status may have an entity or not. */
  def mayHaveEntity(status: Int): Boolean = status match {
    case CONTINUE | SWITCHING_PROTOCOLS | NO_CONTENT | NOT_MODIFIED =>
      false
    case _ =>
      true
  }

  /**
   * Cancel the entity.
   *
   * While theoretically, an Akka streams Source is not supposed to hold resources, in practice, this is very often not
   * the case, for example, the response from an Akka HTTP client may have an associated Source that must be consumed
   * (or cancelled) before the associated connection can be returned to the connection pool.
   */
  def cancelEntity(entity: HttpEntity)(implicit mat: Materializer) = {
    entity match {
      case HttpEntity.Chunked(chunks, _) => chunks.runWith(Sink.cancelled)
      case HttpEntity.Streamed(data, _, _) => data.runWith(Sink.cancelled)
      case _ =>
    }
  }

  /**
   * The connection header logic to use for the result.
   */
  sealed trait ConnectionHeader {
    def willClose: Boolean
    def header: Option[String]
  }
  /**
   * A `Connection: keep-alive` header should be sent. Used to
   * force an HTTP 1.0 connection to remain open.
   */
  case object SendKeepAlive extends ConnectionHeader {
    override def willClose = false
    override def header = Some(KEEP_ALIVE)
  }
  /**
   * A `Connection: close` header should be sent. Used to
   * force an HTTP 1.1 connection to close.
   */
  case object SendClose extends ConnectionHeader {
    override def willClose = true
    override def header = Some(CLOSE)
  }
  /**
   * No `Connection` header should be sent. Used on an HTTP 1.0
   * connection where the default behavior is to close the connection,
   * or when the response already has a Connection: close header.
   */
  case object DefaultClose extends ConnectionHeader {
    override def willClose = true
    override def header = None
  }
  /**
   * No `Connection` header should be sent. Used on an HTTP 1.1
   * connection where the default behavior is to keep the connection
   * open.
   */
  case object DefaultKeepAlive extends ConnectionHeader {
    override def willClose = false
    override def header = None
  }

  // Values for the Connection header
  private val KEEP_ALIVE = "keep-alive"
  private val CLOSE = "close"

  /**
   * Bake the cookies and prepare the new Set-Cookie header.
   */
  def prepareCookies(requestHeader: RequestHeader, result: Result, httpConfiguration: HttpConfiguration): Result = {
    val sessionBaker = new DefaultSessionCookieBaker(httpConfiguration.session)
    val flashBaker = new DefaultFlashCookieBaker(httpConfiguration.flash, httpConfiguration.session)

    result.bakeCookies(sessionBaker, flashBaker, !requestHeader.flash.isEmpty)
  }

  /**
   * Given a map of headers, split it into a sequence of individual headers.
   * Most headers map into a single pair in the new sequence. The exception is
   * the `Set-Cookie` header which we split into a pair for each cookie it
   * contains. This allows us to work around issues with clients that can't
   * handle combined headers. (Also RFC6265 says multiple headers shouldn't
   * be folded together, which Play's API unfortunately  does.)
   */
  def splitSetCookieHeaders(headers: Map[String, String]): Iterable[(String, String)] = {
    if (headers.contains(SET_COOKIE)) {
      // Rewrite the headers with Set-Cookie split into separate headers
      headers.to[Seq].flatMap {
        case (SET_COOKIE, value) =>
          val cookieParts = Cookies.SetCookieHeaderSeparatorRegex.split(value)
          cookieParts.map { cookiePart =>
            SET_COOKIE -> cookiePart
          }
        case (name, value) =>
          Seq((name, value))
      }
    } else {
      // No Set-Cookie header so we can just use the headers as they are
      headers
    }
  }
}

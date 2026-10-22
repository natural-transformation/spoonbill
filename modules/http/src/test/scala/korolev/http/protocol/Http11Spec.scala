package spoonbill.http.protocol

import org.scalacheck._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration
import spoonbill.data.Bytes
import spoonbill.effect.{Decoder, Stream}
import spoonbill.web.{Headers, PathAndQuery, Request, Response}

class Http11Spec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  val http11 = new Http11[Bytes]

  "response serialization" should "reject unsafe arbitrary fields and status phrases before emitting bytes" in {
    val controls = ((0 to 31).filterNot(_ == 9) :+ 127).map(_.toChar.toString)
    controls.foreach { control =>
      intercept[IllegalArgumentException] {
        http11.renderResponseHeader(Response.Status.Ok, Seq("X-Test" -> s"before${control}after"))
      }
      intercept[IllegalArgumentException] {
        http11.renderResponseHeader(new Response.Status(200, s"OK${control}X-Canary: yes"), Nil)
      }
    }
    Seq("", "X:Canary", "X Canary", "X\r\nCanary", "X-ü").foreach { name =>
      intercept[IllegalArgumentException](http11.renderResponseHeader(Response.Status.Ok, Seq(name -> "yes")))
    }
  }

  it should "preserve repeated Set-Cookie fields and redirects" in {
    val headers = Seq(
      Headers.setCookie("deviceId", "safe-device", "/", 60),
      Headers.setCookie("auth", "safe-token", "/", 60),
      Headers.Location -> "/login?continue=%2Faccount"
    )
    val rendered = http11.renderResponseHeader(Response.Status.SeeOther, headers)
    rendered.split("\r\n").count(_.startsWith("Set-Cookie: ")) shouldBe 2
    rendered should include("Location: /login?continue=%2Faccount\r\n")
  }

  "request parsing" should "retain repeated cookie fields so duplicate identities remain ambiguous" in {
    val bytes =
      Bytes.wrap("GET / HTTP/1.1\r\nCookie: deviceId=first\r\ncOoKiE: deviceId=last\r\n\r\n".getBytes("US-ASCII"))
    val (_, request) = http11.parseRequest(bytes, http11.findLastHeaderEnd(bytes))
    request.cookie("deviceId") shouldBe None
  }

  it should "reject malformed request lines and fields without scanning another line as their value" in {
    val malformed = Seq(
      "garbage\r\n\r\n",
      "GET  HTTP/1.1\r\n\r\n",
      "GET /\r\n\r\n",
      "GET / HTTP/1.1 extra\r\n\r\n",
      "GET /bad\tpath HTTP/1.1\r\n\r\n",
      "GET / HTTP/1.1\rX: value\r\n\r\n",
      "GET / HTTP/1.1\r\nBad\r\n\r\n",
      "GET / HTTP/1.1\r\nBad\r\nHost: valid\r\n\r\n",
      "GET / HTTP/1.1\r\nBad\r\n\r\nBody: not-a-header\r\n",
      "GET / HTTP/1.1\r\n: value\r\n\r\n",
      "GET / HTTP/1.1\r\nX: value\rY: next\r\n\r\n",
      "GET / HTTP/1.1\r\nX: value\nOther: injected\r\n\r\n",
      "GET / HTTP/1.1\r\nX : value\r\n\r\n"
    )
    malformed.foreach { input =>
      val bytes = Bytes.wrap(input.getBytes("US-ASCII"))
      intercept[IllegalArgumentException](http11.decodeRequest(Bytes.empty, bytes))
    }
  }

  it should "preserve valid requests without fields, empty field values, and untouched body bytes" in {
    val body = "body: not a header\r\n\r\n"
    for (version <- Seq("HTTP/1.0", "HTTP/1.1")) {
      val bytes = Bytes.wrap(s"POST /plain?x=yes $version\r\n\r\n$body".getBytes("US-ASCII"))
      val (remaining, request) = http11.parseRequest(bytes, http11.findLastHeaderEnd(bytes))
      request.method shouldBe Request.Method.Post
      request.param("x") shouldBe Some("yes")
      request.headers shouldBe empty
      remaining.asAsciiString shouldBe body
    }
    val bytes = Bytes.wrap("GET / HTTP/1.1\r\nX-Empty:\r\n\r\n".getBytes("US-ASCII"))
    http11.parseRequest(bytes, http11.findLastHeaderEnd(bytes))._2.header("X-Empty") shouldBe Some("")
  }

  it should "accept the exact header budget including a split delimiter and preserve a large body" in {
    val header = Bytes.wrap("POST / HTTP/1.1\r\nContent-Length: 65536\r\n\r\n".getBytes("US-ASCII"))
    val parser = new Http11[Bytes](header.length.toInt)
    val body = Bytes.wrap(Array.fill[Byte](65536)(42))
    val (buffer, action) = parser.decodeRequest(Bytes.empty, header.slice(0, header.length - 1))
    action shouldBe Decoder.Action.TakeNext
    val (_, completed) = parser.decodeRequest(buffer, header.slice(header.length - 1, header.length) ++ body)
    completed match {
      case Decoder.Action.Fork(request, remaining) =>
        request.contentLength shouldBe Some(65536L)
        remaining shouldBe body
      case other => fail(s"Expected a complete request, got $other")
    }
  }

  it should "reject an over-budget header both in one read and across many reads" in {
    val header = Bytes.wrap("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes("US-ASCII"))
    val parser = new Http11[Bytes](header.length.toInt - 1)
    intercept[IllegalArgumentException](parser.decodeRequest(Bytes.empty, header))
    var buffer = Bytes.empty
    (0L until header.length - 2).foreach { i =>
      buffer = parser.decodeRequest(buffer, header.slice(i, i + 1))._1
    }
    intercept[IllegalArgumentException](parser.decodeRequest(buffer, header.slice(header.length - 2, header.length)))
    intercept[IllegalArgumentException](parser.decodeRequest(Bytes.empty, Bytes.wrap(Array.fill[Byte](1024 * 1024)(65))))
  }

  "response header bounds" should "include a split delimiter without counting a buffered body against the cap" in {
    val header = Bytes.wrap("HTTP/1.1 200 OK\r\nContent-Length: 65536\r\n\r\n".getBytes("US-ASCII"))
    val body = Bytes.wrap(Array.fill[Byte](65536)(42))
    val parser = new Http11[Bytes](maxResponseHeaderBytes = header.length.toInt)
    val (buffer, pending) = parser.decodeResponse(Bytes.empty, header.slice(0, header.length - 1))
    pending shouldBe Decoder.Action.TakeNext
    parser.decodeResponse(buffer, header.slice(header.length - 1, header.length) ++ body)._2 match {
      case Decoder.Action.Fork(response, remaining) =>
        response.contentLength shouldBe Some(65536L)
        remaining shouldBe body
      case other => fail(s"Expected complete response, got $other")
    }
    val small = new Http11[Bytes](maxResponseHeaderBytes = header.length.toInt - 1)
    intercept[IllegalArgumentException](small.decodeResponse(Bytes.empty, header ++ body))
    var accumulated = Bytes.empty
    (0L until header.length - 2).foreach { index =>
      accumulated = small.decodeResponse(accumulated, header.slice(index, index + 1))._1
    }
    intercept[IllegalArgumentException](small.decodeResponse(accumulated, header.slice(header.length - 2, header.length)))
    intercept[IllegalArgumentException](small.decodeResponse(Bytes.empty, Bytes.wrap(Array.fill[Byte](1024 * 1024)(65))))
  }

  it should "reject malformed and ambiguous response framing before constructing a body" in {
    Seq(
      "garbage\r\n\r\n",
      "HTTP/1.1 200 OK\r\nBad\r\nX: yes\r\n\r\n",
      "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n",
      "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n",
      "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n"
    ).foreach { value =>
      intercept[IllegalArgumentException](http11.decodeResponse(Bytes.empty, Bytes.wrap(value.getBytes("US-ASCII"))))
    }
  }

  // TODO use from effect
  implicit val ec: ExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable): Unit     = runnable.run()
    def reportFailure(cause: Throwable): Unit = cause.printStackTrace()
  }

  "decodeLimitedBody" should "pass bytes if content length not reached and byteTotal is zero" in {
    val bytes               = Bytes(1, 2, 3, 4, 5)
    val contentLength       = 10L
    val (bytesTotal, frame) = http11.decodeLimitedBody(0, bytes, contentLength)
    bytesTotal shouldEqual 5
    frame shouldEqual Decoder.Action.Push(bytes)
  }

  it should "pass part of bytes and take back rest of bytes if content length reached" in {
    val bytes         = Bytes(1, 2, 3, 4, 5)
    val contentLength = 10L
    val (_, frame)    = http11.decodeLimitedBody(7, bytes, contentLength)
    frame shouldEqual Decoder.Action.ForkFinish(
      bytes.slice(0, 3),
      bytes.slice(3, bytes.length)
    )
  }

  it should "finish the stream if count of bytes in chunk is equals to content length" in {
    val bytes         = Bytes(1, 2, 3)
    val contentLength = 10L
    val (_, frame)    = http11.decodeLimitedBody(7, bytes, contentLength)
    frame shouldEqual Decoder.Action.PushFinish(bytes)
  }

  // Request/Response

  private val genCookieOrParam =
    for {
      k <- Gen.asciiPrintableStr.filter(_.nonEmpty)
      v <- Gen.asciiPrintableStr
    } yield (k, v)

  private val genHeader =
    for {
      k <- Gen.alphaNumStr.filter(_.nonEmpty)
      v <- Gen.asciiPrintableStr
    } yield (k, v)

  private val genStatus =
    for {
      k <- Gen.choose(100, 999)
      v <- Gen.alphaNumStr.filter(_.nonEmpty)
    } yield Response.Status(k, v.toUpperCase)

  private val genResponse =
    for {
      // These round trips generate actual body bytes, so use body-bearing final
      // responses; informational and bodyless statuses are covered separately.
      status <- genStatus.filter(s => s.code >= 200 && s.code != 204 && s.code != 304)
      //pathStrings <- Gen.listOf(Gen.asciiPrintableStr)
      //path = Path.fromString(pathStrings.mkString("/"))
      headers    <- Gen.listOf(genHeader)
      bytes      <- Gen.listOf(Gen.choose(Byte.MinValue, Byte.MaxValue))
      bytesVector = Bytes.wrap(bytes.toArray)
    } yield Stream(bytesVector)
      .mat()
      .map { bodyStream =>
        bytesVector -> Response(
          status = status,
          headers = headers,
          body = bodyStream,
          contentLength = Some(bytes.length.toLong)
        )
      }

  private val genRequest =
    for {
      method      <- Gen.oneOf(Request.Method.All)
      pathStrings <- Gen.listOf(Gen.alphaNumStr)
      pq           = PathAndQuery.fromString(pathStrings.mkString("/"))
      headers     <- Gen.listOf(genHeader)
      cookies     <- Gen.listOf(genCookieOrParam)
      params      <- Gen.listOf(genCookieOrParam)
      bytes       <- Gen.listOf(Gen.choose(Byte.MinValue, Byte.MaxValue))
      bytesVector  = Bytes.wrap(bytes.toArray)
    } yield Stream(bytesVector)
      .mat()
      .map { bodyStream =>
        val originalRequest = Request(
          method = method,
          pq = pq,
          headers = headers,
          contentLength = Some(bytes.length.toLong),
          body = bodyStream
        )
        val withCookies = cookies.foldLeft(originalRequest) { case (r, (k, v)) =>
          r.withCookie(k, v)
        }
        val withCookiesAndParams = params.foldLeft(withCookies) { case (r, (k, v)) =>
          r.withParam(k, v)
        }
        bytesVector -> withCookiesAndParams
      }

  private def sliceToChunks(bytes: Bytes, n: Int) = {
    val chunk = bytes.length / n
    (0 until n) map { i =>
      val pos = chunk * i
      if (i < n - 1) bytes.slice(pos, pos + chunk)
      else bytes.slice(pos, bytes.length)
    }
  }

  "renderResponse/parseResponse" should "comply with the law `parse(render(response)) == response`" in {
    forAll(genResponse) { generated =>
      val (responseNoBody, bodyBytes, (parsedBodyBytes, parsedResponse)) = await {
        for {
          (bodyBytes, response) <- generated
          bytesStream           <- http11.renderResponse(response)
          bytes                 <- bytesStream.fold(Bytes.empty)(_ ++ _)
          //_ = println(bytes.asciiString.replaceAll("\r", "\\\\r"))
          lhe = http11.findLastHeaderEnd(bytes)
        } yield {
          val responseNoBody = response.copy(body = ())
          (responseNoBody, bodyBytes, http11.parseResponse(bytes, lhe))
        }
      }
      assert(
        (parsedBodyBytes == bodyBytes) &&
          parsedResponse == responseNoBody
      )
    }
  }

  "renderResponse/decodeResponse" should "comply with rule `decode(render(response)) == response`" in {
    // FIXME fomkin: I do not understand why Gen.chooseNum gives values < 1
    forAll(genResponse, Gen.chooseNum(2, 20).filter(i => i > 1)) { (generated, numChunks) =>
      val (sampleResponse, decodedResponse, sampleBody, decodedBody) = await {
        for {
          (sampleBody, sampleResponse) <- generated
          renderedResponseAsStream     <- http11.renderResponse(sampleResponse)
          renderedResponseAsBytes      <- renderedResponseAsStream.fold(Bytes.empty)(_ ++ _)
          renderedResponseAsChunks     <- Stream(sliceToChunks(renderedResponseAsBytes, numChunks): _*).mat()
          parser                        = new Http11[Bytes](maxResponseHeaderBytes = math.max(4, renderedResponseAsBytes.length.toInt))
          maybeDecodedResponse         <- parser.decodeResponse(Decoder(renderedResponseAsChunks)).pull()
          decodedResponse               = maybeDecodedResponse.getOrElse(throw new Exception("No response found in rendered stream"))
          decodedBody                  <- decodedResponse.body.fold(Bytes.empty)(_ ++ _)
        } yield (
          sampleResponse.copy(body = ()),
          decodedResponse.copy(body = ()),
          sampleBody,
          decodedBody
        )
      }
      assert(
        sampleResponse == decodedResponse &&
          (sampleBody == decodedBody)
      )
    }
  }

  "renderRequest/parseRequest" should "comply with the law `parse(render(request)) == request`" in {
    forAll(genRequest) { generated =>
      val (requestNoBody, bodyBytes, (parsedBodyBytes, parsedRequest)) = await {
        for {
          (bodyBytes, request) <- generated
          bytesStream          <- http11.renderRequest(request)
          bytes                <- bytesStream.fold(Bytes.empty)(_ ++ _)
          lhe                   = http11.findLastHeaderEnd(bytes)
        } yield {
          val responseNoBody = request.copy(body = ())
          (responseNoBody, bodyBytes, http11.parseRequest(bytes, lhe))
        }
      }
      assert(
        (parsedBodyBytes == bodyBytes) &&
          parsedRequest == requestNoBody
      )
    }
  }

  "renderRequest/decodeRequest" should "comply with rule `decode(render(request)) == request`" in {
    // Generated headers may exceed the production default. Exercise round-trip
    // decoding with a sufficient budget; the boundary cases are tested above.
    forAll(genRequest, Gen.chooseNum(2, 20).filter(i => i > 1)) { (generated, numChunks) =>
      val (sampleRequest, decodedRequest, sampleBody, decodedBody) = await {
        for {
          (sampleBody, sampleRequest) <- generated
          renderedRequestAsStream     <- http11.renderRequest(sampleRequest)
          renderedRequestAsBytes      <- renderedRequestAsStream.fold(Bytes.empty)(_ ++ _)
          renderedRequestAsChunks     <- Stream(sliceToChunks(renderedRequestAsBytes, numChunks.max(1)): _*).mat()
          parser                       = new Http11[Bytes](math.max(4, renderedRequestAsBytes.length.toInt))
          maybeDecodedRequest         <- parser.decodeRequest(Decoder(renderedRequestAsChunks)).pull()
          decodedRequest               = maybeDecodedRequest.getOrElse(throw new Exception("No request found in rendered stream"))
          decodedBody                 <- decodedRequest.body.fold(Bytes.empty)(_ ++ _)
        } yield (
          sampleRequest.copy(body = ()),
          decodedRequest.copy(body = ()),
          sampleBody,
          decodedBody
        )
      }
      assert(
        sampleRequest == decodedRequest &&
          (sampleBody == decodedBody)
      )
    }
  }

  // FIXME https://github.com/scalatest/scalatest/issues/1320
  private def await[T](process: Future[T]): T =
    process
      .ready(Duration.Inf)(null)
      .value
      .get
      .get
}

//package spoonbill.server
//
//import spoonbill.data.Bytes
//import spoonbill.effect.{Effect, Stream}
//import spoonbill.effect.syntax._
//import spoonbill.web.Response
//import spoonbill.web.Response.Status
//
//package object internal {
//
//  def HttpResponse[F[_]: Effect](status: Status): HttpResponse[F] = {
//    new Response(status, Stream.empty, Nil, Some(0L))
//  }
//
//  def HttpResponse[F[_]: Effect](
//    status: Status,
//    body: Array[Byte],
//    headers: Seq[(String, String)]
//  ): F[HttpResponse[F]] =
//    Stream(Bytes.wrap(body))
//      .mat[F]()
//      .map(lb => new Response(status, lb, headers, Some(body.length.toLong)))
//
//  def HttpResponse[F[_]: Effect](status: Status, message: String, headers: Seq[(String, String)]): F[HttpResponse[F]] =
//    HttpResponse(status, message.getBytes(java.nio.charset.StandardCharsets.UTF_8), headers)
//}

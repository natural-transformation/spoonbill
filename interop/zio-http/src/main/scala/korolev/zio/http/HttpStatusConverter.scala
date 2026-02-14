package spoonbill.zio.http

import spoonbill.web.Response.{Status => KStatus}
import zio.http.Status

object HttpStatusConverter {

  def fromSpoonbillStatus(kStatus: KStatus): Status =
    Status.fromInt(kStatus.code)
}

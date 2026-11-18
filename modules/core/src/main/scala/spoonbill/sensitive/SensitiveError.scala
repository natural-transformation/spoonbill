package spoonbill.sensitive

enum SensitiveError {
  case InvalidName, InvalidLimits, InvalidPayload, PayloadTooLarge, InvalidTotpUri, InvalidQrMatrix
  case InvalidDeadline, WrongBinding, DeadlineExceeded, NotExpired, InvalidTransition, Closed
}

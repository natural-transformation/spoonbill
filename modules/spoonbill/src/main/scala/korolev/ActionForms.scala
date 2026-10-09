package spoonbill

import avocet.{Document, XmlNs}
import avocet.events.EventPhase
import spoonbill.action.*
import spoonbill.effect.Effect
import spoonbill.effect.syntax.*

/**
 * Captures declared input in the submit message. Authenticated bindings require
 * a configured guarded runtime in addition to the action's session authority.
 */
object ActionForms {
  def onSubmit[F[_]: Effect, S, M, I](
    action: PublicAction[F, S, I],
    onRejected: ActionRejection => S => S
  ): Document.Attr[Context.Binding[F, S, M]] =
    bind(action.descriptor, onRejected) { access =>
      for {
        submitted <- access.submittedFields
        binding <- access.actionBinding
        result <- ActionDispatcher.public(action, submitted, binding)
      } yield (binding, result)
    }

  def onSubmit[F[_]: Effect, S, M, I, P](
    action: AuthenticatedAction[F, S, I, P],
    authority: SessionAuthority[F, P],
    onRejected: ActionRejection => S => S
  ): Document.Attr[Context.Binding[F, S, M]] =
    bind(action.descriptor, onRejected) { access =>
      for {
        submitted <- access.submittedFields
        binding <- access.authenticatedActionBinding
        result <- ActionDispatcher.authenticated(action, submitted, binding, authority)
      } yield (binding, result)
    }

  private def bind[F[_]: Effect, S, M](
    descriptor: ActionDescriptor,
    onRejected: ActionRejection => S => S
  )(dispatch: Context.Access[F, S, M] => F[(InvocationBinding, InvocationResult[S])]): Document.Attr[Context.Binding[F, S, M]] = {
    val fields = descriptor.fields.map { field =>
      val kind = if (field.kind == InputKind.Checkbox) "checkbox" else "text"
      // FieldName has a bounded ASCII grammar, excluding JSON delimiters.
      s"""["${field.name.value}","$kind"]"""
    }.mkString("[", ",", "]")
    val event = Context.Event[F, S, M](
      "submit",
      EventPhase.Bubbling,
      stopPropagation = true,
      access =>
        for {
          dispatched <- dispatch(access)
          (binding, result) = dispatched
          _ <- result match {
                 case InvocationResult.Completed(UiOutcome.Updated(update)) => access.transition(update.apply)
                 case InvocationResult.Completed(UiOutcome.CommitAuthentication(id)) => access.completeAuthentication(id)
                 case InvocationResult.Completed(UiOutcome.PresentSensitive(region, purpose, disclosure, lifetime, onResult)) =>
                   disclosure.consume[F](binding.sensitiveOwner)(payload => access.presentSensitive(region, purpose, payload, lifetime))
                     .flatMap(result => access.transition(onResult(result).apply))
                 case InvocationResult.Completed(UiOutcome.Invalid(error)) =>
                   access.transition(onRejected(ActionRejection.Input(error)))
                 case InvocationResult.Rejected(reason)    => access.transition(onRejected(reason))
                 case InvocationResult.Superseded()          => Effect[F].unit
                 case InvocationResult.OutputSuppressed(_) => Effect[F].unit
               }
        } yield ()
    )
    // Avocet emits attributes before children regardless of argument order.
    // A Node here would write the metadata after the form's opening tag closed.
    Document.Attr { rc =>
      rc.setAttr(XmlNs.html, "data-spoonbill-action-fields", fields)
      rc.addMisc(event)
    }
  }
}

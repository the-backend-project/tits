module com.github.thxmasj.statemachine.templates {
  requires com.github.thxmasj.statemachine.http.inbox;
  requires com.github.thxmasj.statemachine.http.outbox;

  exports com.github.thxmasj.statemachine.templates.cardpayment;
  exports com.github.thxmasj.statemachine.templates.cardpayment.validators;
}

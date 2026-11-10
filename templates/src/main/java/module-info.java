module com.github.thxmasj.backend.templates {
  requires com.github.thxmasj.backend.diagram;
  requires com.github.thxmasj.backend.http.inbox;
  requires com.github.thxmasj.backend.http.outbox;

  exports com.github.thxmasj.backend.templates.cardpayment;
  exports com.github.thxmasj.backend.templates.cardpayment.validators;
}

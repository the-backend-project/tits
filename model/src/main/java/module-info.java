module com.github.thxmasj.statemachine.model {
  requires transitive com.fasterxml.jackson.databind;
  requires transitive java.sql;
  requires transitive org.reactivestreams;
  requires transitive reactor.core;
  requires com.fasterxml.jackson.datatype.jsr310;

  exports com.github.thxmasj.statemachine;
}

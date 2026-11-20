package com.github.thxmasj.backend.http.inbox;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntityModelGroup;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// The `RequestRouting` entity model of an inbox, routing to its requests. The models of all inboxes have the same id,
/// by which their entities are stored, and are in the group [#RequestRouting].
final class RequestRoutingModel implements EntityModel {

  /// The routing model of any inbox, which an inbox request responds to by triggering [HttpInbox#ResponseCreated] on
  /// it, identified by the session
  static final EntityModelGroup RequestRouting = EntityModelGroup.of(
      "RequestRouting",
      UUID.fromString("7c2e9a41-5d8b-4f36-b0e7-1a4c6f8d2b95"),
      model -> model instanceof RequestRoutingModel
  );

  private static final UUID ID = UUID.fromString("20755705-fc81-4228-a1a7-5e13d6e3c153");

  private final Map<State, List<TransitionModel<?, ?>>> transitions;

  RequestRoutingModel(List<HttpInboxRequest<?>> requests) {
    this.transitions = TransitionModels.requestRoutingTransitions(requests);
  }

  @Override
  public String name() {
    return "RequestRouting";
  }

  @Override
  public UUID id() {
    return ID;
  }

  @Override
  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return transitions;
  }

  @Override
  public String toString() {
    return name();
  }
}

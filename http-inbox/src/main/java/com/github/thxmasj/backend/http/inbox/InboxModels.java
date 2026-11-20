package com.github.thxmasj.backend.http.inbox;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.internal.Memoized;
import com.github.thxmasj.backend.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;

/// The entity models of an inbox. The responses of the requests are checked when the entity models are first asked
/// for, e.g. by the state machine, rather than when the inbox is created, which asks for no transitions, so that the
/// inbox may be a constant declared before the models of the application and what their transitions refer to.
final class InboxModels implements HttpInbox {

  private final EntityModel requestRouting;
  private final List<HttpInboxRequest<?>> requests;
  private final Memoized<List<EntityModel>> entityModels;
  private final EventTrigger<HttpRequest<byte[]>, HttpRequest<byte[]>, ?> trigger;

  private InboxModels(List<HttpInboxRequest<?>> requests) {
    this.requestRouting = new RequestRoutingModel(requests);
    this.requests = List.copyOf(requests);
    this.entityModels = new Memoized<>(this::checkedEntityModels, "entity models of the inbox");
    this.trigger = EventTrigger.trigger(RequestReceived, requestRouting);
  }

  static InboxModels of(List<HttpInboxRequest<?>> requests) {
    return new InboxModels(requests);
  }

  @Override
  public EntityModel requestRouting() {
    return requestRouting;
  }

  @Override
  public List<HttpInboxRequest<?>> requests() {
    return requests;
  }

  /// @throws IllegalArgumentException if an inbox request doesn't take a response its processes may give
  @Override
  public List<EntityModel> entityModels() {
    return entityModels.get();
  }

  @Override
  public EventTrigger<HttpRequest<byte[]>, HttpRequest<byte[]>, ?> trigger() {
    return trigger;
  }

  private List<EntityModel> checkedEntityModels() {
    TransitionModels.checkResponses(requests);
    var models = new ArrayList<EntityModel>();
    models.add(requestRouting);
    models.addAll(requests);
    models.add(MessageIds);
    models.add(Index);
    return List.copyOf(models);
  }
}

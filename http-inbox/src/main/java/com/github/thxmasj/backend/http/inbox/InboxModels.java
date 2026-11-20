package com.github.thxmasj.backend.http.inbox;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import java.util.ArrayList;
import java.util.List;

/// The entity models of an inbox
record InboxModels(
    EntityModel requestRouting,
    List<HttpInboxRequest<?>> requests,
    List<EntityModel> entityModels,
    EventTrigger<HttpRequestMessage, HttpRequestMessage, ?> trigger
) implements HttpInbox {

  static InboxModels of(List<HttpInboxRequest<?>> requests) {
    var requestRouting = new RequestRoutingModel(requests);
    TransitionModels.checkResponses(requests);
    var entityModels = new ArrayList<EntityModel>();
    entityModels.add(requestRouting);
    entityModels.addAll(requests);
    entityModels.add(MessageIds);
    entityModels.add(Index);
    return new InboxModels(
        requestRouting,
        List.copyOf(requests),
        List.copyOf(entityModels),
        EventTrigger.trigger(RequestReceived, requestRouting)
    );
  }
}

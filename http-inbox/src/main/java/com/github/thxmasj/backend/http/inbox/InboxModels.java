package com.github.thxmasj.backend.http.inbox;

import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.EventTrigger.EventSpec;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/// The entity models of an inbox
record InboxModels(
    EntityModel requestRouting,
    List<HttpInboxRequest<?>> requests,
    List<EntityModel> entityModels,
    EventTrigger<HttpRequestMessage, HttpRequestMessage, ?> trigger
) implements HttpInbox {

  static InboxModels of(List<HttpInboxRequest<?>> requests, List<? extends EntityModel> application) {
    var requestRouting = new RequestRoutingModel(requests);
    var entityModels = new ArrayList<EntityModel>(application);
    entityModels.add(requestRouting);
    entityModels.addAll(requests);
    entityModels.add(MessageIds);
    entityModels.add(Index);
    TransitionModels.checkResponses(requests, entityModels);
    return new InboxModels(
        requestRouting,
        List.copyOf(requests),
        List.copyOf(entityModels),
        new EventTrigger<>(new EventSpec<>(RequestReceived, Function.identity()), List.of(newEntityId()), requestRouting, false)
    );
  }
}

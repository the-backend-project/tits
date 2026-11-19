package com.github.thxmasj.backend.http.inbox;

import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.EventTrigger.EventSpec;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
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
    // Each once, also if the application gives the inbox's own, e.g. the index
    Set<EntityModel> entityModels = Collections.newSetFromMap(new IdentityHashMap<>());
    var ordered = new ArrayList<EntityModel>();
    for (EntityModel model : concat(application, List.of(requestRouting), requests, List.of(MessageIds, Index)))
      if (entityModels.add(model))
        ordered.add(model);
    TransitionModels.checkResponses(requests, ordered);
    return new InboxModels(
        requestRouting,
        List.copyOf(requests),
        List.copyOf(ordered),
        new EventTrigger<>(new EventSpec<>(RequestReceived, Function.identity()), List.of(newEntityId()), requestRouting, false)
    );
  }

  @SafeVarargs
  private static List<EntityModel> concat(List<? extends EntityModel>... lists) {
    var all = new ArrayList<EntityModel>();
    for (var list : lists)
      all.addAll(list);
    return all;
  }
}

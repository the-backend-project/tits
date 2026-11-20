package com.github.thxmasj.backend.backoffice;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.thxmasj.backend.backoffice.Graph.Conditional;
import com.github.thxmasj.backend.backoffice.Graph.Entity;
import com.github.thxmasj.backend.backoffice.Graph.Listed;
import com.github.thxmasj.backend.backoffice.Graph.Match;
import com.github.thxmasj.backend.backoffice.Graph.StoredEvent;
import com.github.thxmasj.backend.backoffice.Graph.View;
import com.github.thxmasj.backend.backoffice.Sessions.User;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventReference;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.Tuples.Tuple3;
import com.github.thxmasj.backend.core.Tuples.Tuple4;
import com.github.thxmasj.backend.http.HttpMessage;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/// The pages of the backoffice, as HTML for [htmx](https://htmx.org). A page is a whole document when the browser
/// navigates to it, also by a boosted link, and a fragment of it when htmx requests a part of a page, e.g. the entity
/// of a reference expanded in place, so the graph of an entity can be followed on a single page.
final class Pages {

  /// The most entities listed on a page of an entity model
  static final int pageSize = 25;
  /// The most characters of the data of an event shown
  private static final int maxDataLength = 64 * 1024;
  private static final Pattern uuidPattern = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

  private final Graph graph;
  /// The name of the backoffice, e.g. "Identity provider backoffice"
  private final String name;

  /// @param name the name of the backoffice, in the header and the title of every page
  Pages(Graph graph, String name) {
    this.graph = graph;
    this.name = name;
  }

  /// A page or fragment, with its status code
  record Page(int status, String html) {}

  /// The page at the path, for the user
  ///
  /// @param fragment whether htmx requests a part of a page rather than the whole document
  Mono<Page> page(String path, Map<String, String> query, User user, boolean fragment) {
    String rest = path.substring(Sessions.Path.length());
    if (rest.equals("/"))
      return Mono.just(new Page(200, document(name, user, home(query.getOrDefault("q", "")))));
    if (rest.equals("/search")) {
      String q = query.getOrDefault("q", "").trim();
      return search(q).map(results -> new Page(200, fragment ? results : document("Search", user, home(q).replace("<!--results-->", results))));
    }
    if (rest.startsWith("/models/")) {
      UUID modelId = Graph.uuid(rest.substring("/models/".length()));
      var model = modelId != null ? graph.model(modelId) : java.util.Optional.<EntityModel>empty();
      if (model.isEmpty())
        return Mono.just(notFound(user, fragment, "No such entity model"));
      UUID before = Graph.uuid(query.get("before"));
      return latest(model.get(), before).map(html -> new Page(200, fragment ? html : document(model.get().name(), user, html)));
    }
    if (rest.startsWith("/entities/")) {
      UUID entityId = Graph.uuid(rest.substring("/entities/".length()));
      if (entityId == null)
        return Mono.just(notFound(user, fragment, "No such entity"));
      return graph.view(entityId)
          .flatMap(view -> entity(view, !fragment))
          .map(html -> new Page(200, fragment ? html : document(title(entityId), user, html)))
          .switchIfEmpty(Mono.fromSupplier(() -> notFound(user, fragment, "No entity has the entity id " + entityId)));
    }
    return Mono.just(notFound(user, fragment, "No such page"));
  }

  /// The page telling that the user has signed out, or must sign in
  String message(String title, String message, String linkText) {
    return document(title, null, """
        <section class="message">
        <p>%s</p>
        <p><a href="%s/">%s</a></p>
        </section>
        """.formatted(escape(message), Sessions.Path, escape(linkText)));
  }

  private Page notFound(User user, boolean fragment, String message) {
    String html = "<p class=\"empty\">" + escape(message) + "</p>";
    return new Page(404, fragment ? html : document("Not found", user, html));
  }

  private String home(String q) {
    var models = new StringBuilder();
    for (EntityModel model : graph.models())
      models.append("<li><a href=\"%s/models/%s\">%s</a></li>\n".formatted(Sessions.Path, model.id(), escape(model.name())));
    return """
        <section>
        <form class="search" action="%1$s/search" method="get" hx-get="%1$s/search" hx-target="#results" hx-push-url="true"
              hx-trigger="submit, input changed delay:400ms from:find input">
          <input type="search" name="q" value="%2$s" placeholder="Entity id, or an id such as a client id" aria-label="Search" autofocus>
          <button type="submit">Search</button>
        </form>
        <div id="results"><!--results--></div>
        </section>
        <section>
        <h2>Entity models</h2>
        <ul class="models">
        %3$s</ul>
        </section>
        """.formatted(Sessions.Path, escape(q), models);
  }

  private Mono<String> search(String q) {
    if (q.isEmpty())
      return Mono.just("");
    return graph.search(q).collectList().map(matches -> {
      if (matches.isEmpty())
        return "<p class=\"empty\">Nothing found for <code>" + escape(q) + "</code></p>";
      var rows = new StringBuilder();
      for (Match match : matches)
        rows.append("<tr><td>%s</td><td>%s</td><td>%s</td></tr>\n".formatted(entityLink(match.entity()), escape(match.entity().modelName()), escape(match.matchedBy())));
      return """
          <table>
          <thead><tr><th>Entity</th><th>Model</th><th>Found by</th></tr></thead>
          <tbody>
          %s</tbody>
          </table>
          """.formatted(rows);
    });
  }

  private Mono<String> latest(EntityModel model, UUID before) {
    return graph.latest(model, before, pageSize + 1).collectList().map(listed -> {
      var rows = new StringBuilder();
      for (Listed entity : listed.subList(0, Math.min(pageSize, listed.size())))
        rows.append("<tr><td>%s</td><td>%s</td><td>%s</td></tr>\n".formatted(entityLink(entity.entity()), time(entity.created()), escape(entity.firstEvent())));
      String more = listed.size() > pageSize ?
          "<p><a href=\"%s/models/%s?before=%s\">Earlier</a></p>".formatted(Sessions.Path, model.id(), listed.get(pageSize - 1).entity().id()) :
          "";
      return """
          <h1>%s</h1>
          <p class="subtitle">The latest entities, by when they were created</p>
          %s
          %s
          """.formatted(
          escape(model.name()),
          listed.isEmpty() ? "<p class=\"empty\">No entities</p>" : """
              <table>
              <thead><tr><th>Entity</th><th>Created</th><th>First event</th></tr></thead>
              <tbody>
              %s</tbody>
              </table>""".formatted(rows),
          more
      );
    });
  }

  /// The entity with its events, ids, index keys and pending triggers, and the entities it refers to and is referred
  /// to by, which can be expanded in place
  ///
  /// @param heading whether the entity has a heading of its own, as on its own page, or is inside another
  private Mono<String> entity(View view, boolean heading) {
    var html = new StringBuilder();
    if (heading) {
      html.append("<h1>%s <code>%s</code></h1>\n".formatted(escape(view.entity().modelName()), view.entity().id()));
    } else {
      html.append("<p class=\"subtitle\"><a href=\"%s/entities/%s\">Open the page of the entity</a></p>\n".formatted(Sessions.Path, view.entity().id()));
    }
    var dataOfEvents = new ArrayList<String>();
    html.append("<h2>Events</h2>\n");
    html.append(events(view.events(), dataOfEvents));
    if (!view.ids().isEmpty()) {
      html.append("<h2>Ids</h2>\n");
      html.append(events(view.ids(), dataOfEvents));
    }
    if (!view.indexKeys().isEmpty()) {
      html.append("<h2>Index keys</h2>\n");
      html.append(events(view.indexKeys(), dataOfEvents));
    }
    if (view.delayed().isPresent() || !view.conditionals().isEmpty()) {
      html.append("<h2>Waiting for</h2>\n<table>\n<thead><tr><th>Event</th><th>When</th><th>Data</th></tr></thead>\n<tbody>\n");
      view.delayed().ifPresent(delayed -> {
        String data = data(delayed.event());
        dataOfEvents.add(data);
        html.append("<tr><td>%s, as event %d</td><td>At %s, attempt %d</td><td>%s</td></tr>\n".formatted(
            escape(delayed.event().typeName()), delayed.eventNumber(), time(delayed.deadline()), delayed.attempt(), data
        ));
      });
      for (Conditional conditional : view.conditionals()) {
        String data = data(conditional.event());
        dataOfEvents.add(data);
        html.append("<tr><td>%s</td><td>When the entities with %s <code>%s</code> have a last event of %s. Triggered by event %d of %s</td><td>%s</td></tr>\n".formatted(
            escape(conditional.event().typeName()),
            escape(conditional.referenceType()),
            escape(bytes(conditional.indexKey())),
            escape(String.join(", ", conditional.lastTypes())),
            conditional.triggerEventNumber(),
            "<code>" + conditional.trigger() + "</code>",
            data
        ));
      }
      html.append("</tbody>\n</table>\n");
    }
    // The entities referred to by the data, which the data links to
    var referred = new LinkedHashSet<UUID>();
    for (String data : dataOfEvents) {
      Matcher matcher = uuidPattern.matcher(data);
      while (matcher.find())
        referred.add(UUID.fromString(matcher.group()));
    }
    referred.remove(view.entity().id());
    return graph.entities(referred).map(entities -> {
      String body = linked(html.toString(), entities.keySet(), view.entity().id());
      var related = new StringBuilder();
      if (!entities.isEmpty()) {
        related.append("<h2>Refers to</h2>\n");
        for (Entity entity : entities.values())
          related.append(expandable(entity, null));
      }
      if (!view.referrers().isEmpty()) {
        related.append("<h2>Referred to by</h2>\n");
        for (var referrer : view.referrers())
          related.append(expandable(referrer.entity(), referrer.indexType()));
      }
      return body + related;
    });
  }

  /// An entity which is loaded and shown in place when expanded
  private static String expandable(Entity entity, String by) {
    return """
        <details class="related" hx-get="%1$s/entities/%2$s" hx-trigger="toggle once" hx-target="find .body">
        <summary>%3$s <code>%2$s</code>%4$s</summary>
        <div class="body"><p class="empty">Loading…</p></div>
        </details>
        """.formatted(Sessions.Path, entity.id(), escape(entity.modelName()), by != null ? " <span class=\"by\">by " + escape(by) + "</span>" : "");
  }

  private static String events(List<StoredEvent> events, List<String> dataOfEvents) {
    if (events.isEmpty())
      return "<p class=\"empty\">None</p>\n";
    var rows = new StringBuilder();
    for (StoredEvent event : events) {
      String data = data(event);
      dataOfEvents.add(data);
      rows.append("<tr><td class=\"number\">%d</td><td>%s</td><td>%s</td><td>%s</td></tr>\n".formatted(
          event.number(), time(event.timestamp()), escape(event.typeName()), data
      ));
    }
    return """
        <table class="events">
        <thead><tr><th>#</th><th>Time</th><th>Event</th><th>Data</th></tr></thead>
        <tbody>
        %s</tbody>
        </table>
        """.formatted(rows);
  }

  /// The data of an event as escaped HTML, unmarshalled by its data type if it is known
  static String data(StoredEvent event) {
    byte[] data = event.data();
    if (data == null || data.length == 0)
      return "";
    String text;
    if (event.type() == null) {
      text = bytes(data);
    } else {
      DataType<?> dataType = event.type().outputDataType();
      if (dataType instanceof DataType.JsonDataType<?>) {
        text = json(data);
      } else {
        try {
          text = value(dataType.unmarshal(data));
        } catch (RuntimeException e) {
          text = bytes(data);
        }
      }
    }
    if (text.isEmpty())
      return "";
    if (text.length() > maxDataLength)
      text = text.substring(0, maxDataLength) + "\n… (" + (text.length() - maxDataLength) + " more characters)";
    return "<pre>" + escape(text) + "</pre>";
  }

  /// The bytes of a message whose body is bytes, or which has none
  @SuppressWarnings("unchecked")
  private static byte[] sent(HttpMessage<?> message) {
    return HttpMessage.toBytes((HttpMessage<byte[]>) message);
  }

  /// A value unmarshalled by a data type other than JSON, as text
  private static String value(Object value) {
    return switch (value) {
      case null -> "";
      case String s -> s;
      case UUID u -> u.toString();
      case EventReference r -> r.entityId() + " event " + r.eventNumber();
      case Tuple2<?, ?> t -> String.join("\n", value(t.t1()), value(t.t2()));
      case Tuple3<?, ?, ?> t -> String.join("\n", value(t.t1()), value(t.t2()), value(t.t3()));
      case Tuple4<?, ?, ?, ?> t -> String.join("\n", value(t.t1()), value(t.t2()), value(t.t3()), value(t.t4()));
      // A message as sent or received, while one with a payload, e.g. an outbox request, is shown as JSON below
      case HttpMessage<?> m when m.body() == null || m.body() instanceof byte[] -> bytes(sent(m));
      case byte[] b -> bytes(b);
      case Number n -> n.toString();
      case Boolean b -> b.toString();
      case Enum<?> e -> e.name();
      default -> {
        try {
          yield DataType.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (RuntimeException e) {
          yield value.toString();
        }
      }
    };
  }

  /// JSON as stored, indented, or as text if it is not JSON
  private static String json(byte[] data) {
    try {
      JsonNode node = DataType.objectMapper.readTree(data);
      return node.isString() ? node.asString() : DataType.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    } catch (RuntimeException e) {
      return bytes(data);
    }
  }

  /// Bytes as text if they are UTF-8, and as hexadecimal otherwise
  static String bytes(byte[] data) {
    if (data == null)
      return "";
    try {
      return UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(data))
          .toString();
    } catch (CharacterCodingException e) {
      return "0x" + HexFormat.of().formatHex(data);
    }
  }

  /// The HTML with the entity ids of the entities linked to their pages
  private static String linked(String html, Set<UUID> entities, UUID self) {
    if (entities.isEmpty())
      return html;
    Matcher matcher = uuidPattern.matcher(html);
    var result = new StringBuilder();
    while (matcher.find()) {
      UUID id = UUID.fromString(matcher.group());
      // Ids inside attributes, i.e. links already, are left as they are
      boolean inAttribute = matcher.start() > 0 && (html.charAt(matcher.start() - 1) == '/' || html.charAt(matcher.start() - 1) == '"');
      if (entities.contains(id) && !id.equals(self) && !inAttribute)
        matcher.appendReplacement(result, Matcher.quoteReplacement("<a href=\"" + Sessions.Path + "/entities/" + id + "\">" + matcher.group() + "</a>"));
      else
        matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private static String entityLink(Entity entity) {
    return "<a href=\"%s/entities/%s\"><code>%s</code></a>".formatted(Sessions.Path, entity.id(), entity.id());
  }

  private static String time(LocalDateTime time) {
    return time != null ? "<time>" + timeFormat.format(time) + "</time>" : "";
  }

  private static String title(UUID entityId) {
    return "Entity " + entityId;
  }

  /// A whole document, with the user signed in, if any, in its header
  String document(String title, User user, String main) {
    String signedIn = user == null ? "" : """
        <form class="user" action="%s/signout" method="post" hx-boost="false">
          <span>%s</span> <button type="submit">Sign out</button>
        </form>""".formatted(Sessions.Path, escape(user.displayName()));
    // htmx is configured not to add styles or evaluate scripts, which the Content-Security-Policy forbids
    return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="htmx-config" content='{"includeIndicatorStyles":false,"allowEval":false,"allowScriptTags":false,"historyCacheSize":0}'>
        <title>%2$s</title>
        <link rel="stylesheet" href="%1$s/static/backoffice.css">
        <script src="%1$s/static/htmx.min.js"></script>
        </head>
        <body hx-boost="true">
        <header>
        <a class="home" href="%1$s/">%3$s</a>
        %4$s
        </header>
        <main>
        %5$s
        </main>
        </body>
        </html>
        """.formatted(Sessions.Path, escape(title.equals(name) ? name : title + " – " + name), escape(name), signedIn, main);
  }

  static String escape(String s) {
    if (s == null)
      return "";
    var result = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&' -> result.append("&amp;");
        case '<' -> result.append("&lt;");
        case '>' -> result.append("&gt;");
        case '"' -> result.append("&quot;");
        case '\'' -> result.append("&#39;");
        default -> result.append(c);
      }
    }
    return result.toString();
  }

}

package com.github.thxmasj.statemachine.deploy;

import com.pulumi.Config;
import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.core.TypeShape;
import com.pulumi.resources.ComponentResourceOptions;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// The Pulumi program deploying an application to Azure Functions, configured by the stack configuration described
/// in `Pulumi.yaml`.
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    Pulumi.run(Main::stack);
  }

  private static void stack(Context ctx) {
    Config config = ctx.config();
    var app = new FunctionsApp(
        config.get("name").orElse(ctx.stackName()),
        new FunctionsAppArgs(
            config.require("image"),
            config.getObject("migrateArgs", TypeShape.list(String.class)).orElse(List.of("migrate")),
            config.getObject("environment", TypeShape.map(String.class, String.class)).orElse(Map.of()),
            config.get("databaseRole").orElse("App"),
            config.get("databaseSchema").orElse(null),
            config.get("databaseCollation").orElse("Norwegian_100_CS_AS_SC_UTF8"),
            config.getDouble("databaseMinCapacity").orElse(0.5),
            config.getInteger("databaseMaxCapacity").orElse(1),
            config.getInteger("databaseAutoPauseDelay").orElse(60),
            config.getBoolean("databaseUseFreeLimit").orElse(false),
            config.getInteger("instanceMemory").orElse(512),
            config.getInteger("maximumInstanceCount").orElse(10),
            config.get("deployerIp").orElseGet(Main::publicIp),
            Path.of("target", "azure", ctx.stackName())
        ),
        ComponentResourceOptions.Empty
    );
    ctx.export("url", app.url());
    ctx.export("resourceGroupName", app.resourceGroupName());
    ctx.export("functionAppName", app.functionAppName());
    ctx.export("databaseServer", app.databaseServer());
    ctx.export("databaseName", app.databaseName());
  }

  private static String publicIp() {
    try (HttpClient client = HttpClient.newHttpClient()) {
      HttpResponse<String> response = client.send(
          HttpRequest.newBuilder(URI.create("https://api.ipify.org")).build(),
          HttpResponse.BodyHandlers.ofString()
      );
      if (response.statusCode() != 200)
        throw new IllegalStateException("Failed to look up public IP address: " + response.statusCode());
      return response.body().strip();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to look up public IP address, set deployerIp instead", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

}

package com.github.thxmasj.backend.helloworld.backoffice;

import com.github.thxmasj.backend.backoffice.BackofficeApp;
import com.github.thxmasj.backend.helloworld.Greetings;

/// The backoffice of the hello world, an application of its own which reads the entities of the
/// hello world from its schema, with the hello world's entity models. See [BackofficeApp].
///
/// Usage:
///
/// - `hello-world-backoffice` serves the backoffice on `$PORT` until terminated.
/// - `hello-world-backoffice migrate` creates the backoffice's schema and grants its role read access to the schema of the
///   hello world, and exits. Run it as a one-off admin process before serving, after the migration of the
///   hello world.
public final class HelloWorldBackoffice {

  private HelloWorldBackoffice() {}

  public static void main(String[] args) {
    BackofficeApp.run("Hello world", "hello_world", Greetings.transitions(), args, System.getenv());
  }

}

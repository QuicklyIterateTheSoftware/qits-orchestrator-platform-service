package eu.wohlben.qits.orchestrator.process;

import java.util.List;

/**
 * A technical process: a named, ordered list of steps that only send requests.
 *
 * <p><b>What a process may do is the whole rule of this repository.</b> It calls other services and
 * records what they said. It holds no socket, opens no store of its own beyond the run log, and
 * makes no decision an owner has not published as an API. A process that wanted to delete something
 * itself would be a second opinion about a rule that lives with the store's owner.
 *
 * <p>Implementations are CDI beans discovered by {@code ProcessRegistry}. Adding one is a class and
 * nothing else.
 */
public interface TechnicalProcess {

  /** The stable kind, used in every route: {@code gc}. */
  String kind();

  /** The human name, e.g. "Garbage collection". */
  String name();

  /** One paragraph a person reads before pressing the button. */
  String description();

  /**
   * The steps, in DECLARATION ORDER — which is the order they run in.
   *
   * <p>Order and edges are separate things. The order is this list; the edges are each step's
   * {@code dependsOn}, and they decide only what a failure skips. A step whose dependencies all
   * succeeded runs when the list reaches it, so a process is read top to bottom.
   */
  List<StepDefinition> steps();
}

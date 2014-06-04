/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.service.launcher;

import com.google.common.base.Preconditions;
import org.apache.hadoop.service.Service;
import org.apache.hadoop.util.ExitUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.hadoop.service.launcher.LauncherExitCodes.EXIT_INTERRUPTED;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles interrupts by shutting down a service, escalating if the service
 * does not shut down in time, or when other interrupts are received.
 * <ol>
 *   <li>The service is given a time in milliseconds to stop:
 *   if it exceeds this it the process exits anyway.</li>
 *   <li>the exit operation used is {@link ServiceLauncher#exit(int, String)}
 *   with the exit code {@link LauncherExitCodes#EXIT_INTERRUPTED}</li>
 *   <li>If a second shutdown signal is received during the shutdown
 *   process, {@link ExitUtil#halt(int)} is invoked. This handles the 
 *   problem of blocking shutdown hooks.</li>
 * </ol>
 * 
 * @param <S> type of service to shut down
 */
public class InterruptEscalator<S extends Service> implements IrqHandler.Interrupted {
  private static final Logger LOG = LoggerFactory.getLogger(
      InterruptEscalator.class);

  /**
   * Flag to indicate when a shutdown signal has already been received.
   * This allows the operation to be escalated
   */
  private final AtomicBoolean signalAlreadyReceived = new AtomicBoolean(false);

  private final ServiceLauncher<S> owner;
  private final int shutdownTimeMillis;

  /**
   * Previous interrupt handlers. These are not queried.
   */
  private final List<IrqHandler> interruptHandlers =
      new ArrayList<IrqHandler>(2);
  private boolean forcedShutdownTimedOut;

  public InterruptEscalator(ServiceLauncher<S> owner, int shutdownTimeMillis) {
    Preconditions.checkArgument(owner != null, "null owner");
    this.owner = owner;
    this.shutdownTimeMillis = shutdownTimeMillis;
  }

  @Override
  public void interrupted(IrqHandler.InterruptData interruptData) {
    String message = "Service interrupted by " + interruptData.toString();
    LOG.warn(message);
    if (!signalAlreadyReceived.compareAndSet(false, true)) {
      message = "Repeated interrupt: escalating to a JVM halt";
      LOG.warn(message);
      // signal already received. On a second request to a hard JVM
      // halt and so bypass any blocking shutdown hooks.
      ExitUtil.halt(LauncherExitCodes.EXIT_INTERRUPTED, message);
    }
    //start an async shutdown thread with a timeout
    ServiceForcedShutdown forcedShutdown =
        new ServiceForcedShutdown();
    Thread thread = new Thread(forcedShutdown);
    thread.setDaemon(true);
    thread.setName("Service Forced Shutdown");
    thread.start();
    //wait for that thread to finish
    try {
      thread.join(shutdownTimeMillis);
    } catch (InterruptedException ignored) {
      //ignored
    }
    forcedShutdownTimedOut = !forcedShutdown.isServiceStopped();
    if (forcedShutdownTimedOut) {
      LOG.warn("Service did not shut down in time");
    }
    owner.exit(EXIT_INTERRUPTED, message);
  }

  /**
   * Register an interrupt handler
   * @param signalName signal name
   * @throws IllegalArgumentException if the registration failed
   */
  public synchronized void register(String signalName) {
    interruptHandlers.add(new IrqHandler(signalName, this));
  }

  /**
   * Look up the handler for a signal
   * @param signalName signal name
   * @return a handler if found
   */
  public synchronized IrqHandler lookup(String signalName) {
    for (IrqHandler irqHandler : interruptHandlers) {
      if (irqHandler.getName().equals(signalName)) {
        return irqHandler;
      }
    }
    return null;
  }

  /**
   * Flag set if forced shut down timed out
   * @return true if a shutdown was attempted and it timed out
   */
  public boolean isForcedShutdownTimedOut() {
    return forcedShutdownTimedOut;
  }

  /**
   * Flag set if a signal has been received
   * @return true if there has been one interrupt already.
   */
  public boolean isSignalAlreadyReceived() {
    return signalAlreadyReceived.get();
  }

  /**
   * forced shutdown runnable.
   */
  protected class ServiceForcedShutdown implements Runnable {

    private final AtomicBoolean serviceStopped =
        new AtomicBoolean(false);

    @Override
    public void run() {
      S service = owner.getService();
      if (service != null) {
        service.stop();
        serviceStopped.set(service.waitForServiceToStop(shutdownTimeMillis));
      } else {
        serviceStopped.set(true);
      }
    }

    private boolean isServiceStopped() {
      return serviceStopped.get();
    }
  }
}

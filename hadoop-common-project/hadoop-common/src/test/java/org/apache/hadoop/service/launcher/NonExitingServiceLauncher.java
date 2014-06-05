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

import org.apache.hadoop.service.Service;
import org.apache.hadoop.util.ExitUtil;

/**
 * Service launcher for testing
 * @param <S> type of service to launch
 */
public class NonExitingServiceLauncher<S extends Service> extends
    ServiceLauncher<S> {

  public ExitUtil.ExitException exitException;

  public NonExitingServiceLauncher(String serviceClassName) {
    super(serviceClassName);
  }

  public void setService(S s) {
    super.setService(s);
  }

  @Override
  protected void exit(ExitUtil.ExitException ee) {
    exitException = ee;
    super.exit(ee);
  }

  @Override
  protected void exit(int exitCode, String message) {
    exit(new ServiceLaunchException(exitCode, message));
  }

}

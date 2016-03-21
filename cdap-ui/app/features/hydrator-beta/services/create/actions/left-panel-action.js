/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

class LeftPanelActionsFactoryBeta {
  constructor(LeftPanelDispatcherBeta) {
    this.dispatcher = LeftPanelDispatcherBeta.getDispatcher();
  }
  expand() {
    this.dispatcher.dispatch('OnLeftPanelToggled', {panelState: true});
  }
  minimize() {
    this.dispatcher.dispatch('OnLeftPanelToggled', {panelState: false});
  }
  togglePanel() {
    this.dispatcher.dispatch('toggleLeftPanelState');
  }
}

LeftPanelActionsFactoryBeta.$inject = ['LeftPanelDispatcherBeta'];
angular.module(`${PKG.name}.feature.hydrator-beta`)
  .service('LeftPanelActionsFactoryBeta', LeftPanelActionsFactoryBeta);
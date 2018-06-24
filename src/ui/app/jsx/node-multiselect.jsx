//
//  Copyright 2018-2018 Stefan Podkowinski
//
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.

import React from "react";
import Multiselect from "react-boostrap-multiselect";

const nodeMultiselect = React.createClass({

  propTypes: {
    onChange: React.PropTypes.func.isRequired,
    clusterStatusResult: React.PropTypes.object.isRequired
  },

  getInitialState: function() {
    return {
      applied: false,
      endpointsByCluster: {}
    };
  },

  componentDidMount: function() {
    this._clusterStatusSubscription = this.props.clusterStatusResult.subscribeOnNext(obs => {
      obs.subscribeOnNext(state => {
        if(!state.nodes_status) {
          console.error("Missing nodes_status result");
          this.replaceState({endpointsByCluster: {}});
          if(this.refs.multiselectRef) {
            // FIXME
            this.refs.multiselectRef.syncData();
          }
          return;
        }
        const dcs = state.nodes_status.endpointStates.map(epState => Object.keys(epState.endpoints));

        const epCurrent = {};
        const epStates = state.nodes_status.endpointStates;
        if(epStates.length == 0) {
          console.error("No endpoint states found");
          this.replaceState({endpointsByCluster: {}});
          this.refs.multiselectRef.syncData();
          return;
        }

        const eps = epStates[0].endpoints;
        const dcGroups = [];
        for(let dc in eps) {
          epCurrent[dc] = Object.values(eps[dc]).map(epsByRack => epsByRack.map(ep => ep.endpoint))
            .reduce(Function.prototype.apply.bind(Array.prototype.concat)); // flatten racks
          const epItems = epCurrent[dc].map(ep => { return {value:ep, label:ep}});
          dcGroups.push({label: dc, children: epItems});
        }
        this.replaceState({endpointsByCluster: dcGroups});
        this.refs.multiselectRef.syncData();
      });
    });
  },

  componentWillUnmount: function() {
    this._clusterStatusSubscription.dispose();
  },

  render: function() {
    if(Object.keys(this.state.endpointsByCluster).length == 0) {
      return <div className="row"/>;
    }

    return (
        <Multiselect onChange={this.props.onChange} ref="multiselectRef" data={this.state.endpointsByCluster} multiple />
    );
  }

});

export default nodeMultiselect;
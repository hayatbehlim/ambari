/**
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

var App = require('app');

App.QueueBadgeComponent = Em.Component.extend({
  layoutName:'components/queueBadge',
  tagName:'span',
  q: null,

  loadedQ: Em.computed.not('q.isSaving'),
  tooltip: Em.computed.and('loadedQ','q.isAnyDirty'),

  warning: Em.computed.alias('q.overCapacity'),

  color:function () {
    var q = this.get('q'),
        color;

    switch (true) {
      case (q.get('isDeletedQueue')):
        color = 'red';
        break;
      case (q.get('isSaving')):
        color = 'gray';
        break;
      case (q.get('isNewQueue')):
        color = 'blue';
        break;
      case (q.get('isError')):
        color = 'red';
        break;
      case (q.get('isAnyDirty')):
        color = 'blue';
        break;
      default:
        color = 'green';
    }

    return color;
  }.property('q.isNewQueue','q.isSaving','q.isError','q.isAnyDirty','q.isDeletedQueue'),
  icon:function () {
    var q = this.get('q'),
        icon;

    switch (true) {
      case (q.get('isDeletedQueue')):
        icon = 'fa-minus';
        break;
      case (q.get('isSaving')):
        icon = 'fa-spinner';
        break;
      case (q.get('isNewQueue')):
        icon = 'fa-refresh';
        break;
      case (q.get('isError')):
        icon = 'fa-warning';
        break;
      case (q.get('isAnyDirty')):
        icon = 'fa-pencil';
        break;
      default:
        icon = 'fa-check';
    }

    return icon;
  }.property('q.isNewQueue','q.isSaving','q.isError','q.isAnyDirty','q.isDeletedQueue')
});
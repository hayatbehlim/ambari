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

package org.apache.ambari.server.controller.internal;


import org.apache.ambari.server.topology.Cardinality;
import org.apache.ambari.server.topology.ClusterTopology;
import org.apache.ambari.server.topology.Configuration;
import org.apache.ambari.server.topology.HostGroupInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Updates configuration properties based on cluster topology.  This is done when exporting
 * a blueprint and when a cluster is provisioned via a blueprint.
 */
public class BlueprintConfigurationProcessor {

  protected final static Logger LOG = LoggerFactory.getLogger(BlueprintConfigurationProcessor.class);

  /**
   * Single host topology updaters
   */
  private static Map<String, Map<String, PropertyUpdater>> singleHostTopologyUpdaters =
      new HashMap<String, Map<String, PropertyUpdater>>();

  /**
   * Multi host topology updaters
   */
  private static Map<String, Map<String, PropertyUpdater>> multiHostTopologyUpdaters =
      new HashMap<String, Map<String, PropertyUpdater>>();

  /**
   * Database host topology updaters
   */
  private static Map<String, Map<String, PropertyUpdater>> dbHostTopologyUpdaters =
      new HashMap<String, Map<String, PropertyUpdater>>();

  /**
   * Updaters for properties which need 'm' appended
   */
  private static Map<String, Map<String, PropertyUpdater>> mPropertyUpdaters =
      new HashMap<String, Map<String, PropertyUpdater>>();

  /**
   * Updaters that preserve the original property value, functions
   *   as a placeholder for DB-related properties that need to be
   *   removed from export, but do not require an update during
   *   cluster creation
   */
  private static Map<String, Map<String, PropertyUpdater>> removePropertyUpdaters =
    new HashMap<String, Map<String, PropertyUpdater>>();

  /**
   * Collection of all updaters
   */
  private static Collection<Map<String, Map<String, PropertyUpdater>>> allUpdaters =
      new ArrayList<Map<String, Map<String, PropertyUpdater>>>();

  /**
   * Compiled regex for hostgroup token.
   */
  private static Pattern HOSTGROUP_REGEX = Pattern.compile("%HOSTGROUP::(\\S+?)%");

  /**
   * Compiled regex for hostgroup token with port information.
   */
  private static Pattern HOSTGROUP_PORT_REGEX = Pattern.compile("%HOSTGROUP::(\\S+?)%:?(\\d+)?");

  /**
   * Statically-defined set of properties that can support using a nameservice name
   *   in the configuration, rather than just a host name.
   */
  private static Set<String> configPropertiesWithHASupport =
    new HashSet<String>(Arrays.asList("fs.defaultFS", "hbase.rootdir", "instance.volumes"));

  /**
   * Statically-defined list of filters to apply on property exports.
   * This will initially be used to filter out the Ranger Passwords, but
   * could be extended in the future for more generic purposes.
   */
  private static final PropertyFilter[] propertyFilters = { new PasswordPropertyFilter() };

  /**
   * Configuration properties to be updated
   */
  //private Map<String, Map<String, String>> properties;

  private ClusterTopology clusterTopology;


  public BlueprintConfigurationProcessor(ClusterTopology clusterTopology) {
    this.clusterTopology = clusterTopology;
  }

  public Collection<String> getRequiredHostGroups() {
    Collection<String> requiredHostGroups = new HashSet<String>();

    for (Map<String, Map<String, PropertyUpdater>> updaterMap : createCollectionOfUpdaters()) {
      for (Map.Entry<String, Map<String, PropertyUpdater>> entry : updaterMap.entrySet()) {
        String type = entry.getKey();
        for (Map.Entry<String, PropertyUpdater> updaterEntry : entry.getValue().entrySet()) {
          String propertyName = updaterEntry.getKey();
          PropertyUpdater updater = updaterEntry.getValue();

          // topo cluster scoped configuration which also includes all default and BP properties
          Map<String, Map<String, String>> clusterProps = clusterTopology.getConfiguration().getFullProperties();
          Map<String, String> typeMap = clusterProps.get(type);
          if (typeMap != null && typeMap.containsKey(propertyName)) {
            requiredHostGroups.addAll(updater.getRequiredHostGroups(
                typeMap.get(propertyName), clusterProps, clusterTopology));
          }

          // host group configs
          for (HostGroupInfo groupInfo : clusterTopology.getHostGroupInfo().values()) {
            Map<String, Map<String, String>> hgConfigProps = groupInfo.getConfiguration().getProperties();
            Map<String, String> hgTypeMap = hgConfigProps.get(type);
            if (hgTypeMap != null && hgTypeMap.containsKey(propertyName)) {
              requiredHostGroups.addAll(updater.getRequiredHostGroups(
                  hgTypeMap.get(propertyName), hgConfigProps, clusterTopology));
            }
          }
        }
      }
    }
    return requiredHostGroups;
  }


  /**
   * Update properties for cluster creation.  This involves updating topology related properties with
   * concrete topology information.
   */
  public void doUpdateForClusterCreate() throws ConfigurationTopologyException {
    Configuration clusterConfig = clusterTopology.getConfiguration();
    Map<String, Map<String, String>> clusterProps = clusterConfig.getFullProperties();
    Map<String, HostGroupInfo> groupInfoMap = clusterTopology.getHostGroupInfo();

    for (Map<String, Map<String, PropertyUpdater>> updaterMap : createCollectionOfUpdaters()) {
      for (Map.Entry<String, Map<String, PropertyUpdater>> entry : updaterMap.entrySet()) {
        String type = entry.getKey();
        for (Map.Entry<String, PropertyUpdater> updaterEntry : entry.getValue().entrySet()) {
          String propertyName = updaterEntry.getKey();
          PropertyUpdater updater = updaterEntry.getValue();

          // topo cluster scoped configuration which also includes all default and BP properties
          Map<String, String> typeMap = clusterProps.get(type);
          if (typeMap != null && typeMap.containsKey(propertyName)) {
            clusterConfig.setProperty(type, propertyName, updater.updateForClusterCreate(
                propertyName, typeMap.get(propertyName), clusterProps, clusterTopology));
          }

          // host group configs
          for (HostGroupInfo groupInfo : groupInfoMap.values()) {
            Configuration hgConfig = groupInfo.getConfiguration();
            Map<String, Map<String, String>> hgConfigProps = hgConfig.getProperties();
            Map<String, String> hgTypeMap = hgConfigProps.get(type);
            if (hgTypeMap != null && hgTypeMap.containsKey(propertyName)) {
              hgConfig.setProperty(type, propertyName, updater.updateForClusterCreate(
                  propertyName, hgTypeMap.get(propertyName), hgConfigProps, clusterTopology));
            }
          }
        }
      }
    }

    //todo: lots of hard coded HA rules included here
    if (clusterTopology.isNameNodeHAEnabled()) {
      // if the active/stanbdy namenodes are not specified, assign them automatically
      if (! isNameNodeHAInitialActiveNodeSet(clusterProps) && ! isNameNodeHAInitialStandbyNodeSet(clusterProps)) {
        Collection<String> nnHosts = clusterTopology.getHostAssignmentsForComponent("NAMENODE");
        if (nnHosts.size() != 2) {
          throw new ConfigurationTopologyException("NAMENODE HA requires exactly 2 hosts running NAMENODE but there are: " +
              nnHosts.size() + " Hosts: " + nnHosts);
        }

        // set the properties that configure which namenode is active,
        // and which is a standby node in this HA deployment
        Iterator<String> nnHostIterator = nnHosts.iterator();
        clusterConfig.setProperty("hadoop-env", "dfs_ha_initial_namenode_active", nnHostIterator.next());
        clusterConfig.setProperty("hadoop-env", "dfs_ha_initial_namenode_standby", nnHostIterator.next());
      }
    }
    setMissingConfigurations(clusterProps);
  }

  /**
   * Update properties for blueprint export.
   * This involves converting concrete topology information to host groups.
   */
  //todo: use cluster topology
  public void doUpdateForBlueprintExport() {

    // HA configs are only processed in cluster configuration, not HG configurations
    if (clusterTopology.isNameNodeHAEnabled()) {
      doNameNodeHAUpdate();
    }

    if (clusterTopology.isYarnResourceManagerHAEnabled()) {
      doYarnResourceManagerHAUpdate();
    }

    if (isOozieServerHAEnabled(clusterTopology.getConfiguration().getFullProperties())) {
      doOozieServerHAUpdate();
    }

    Collection<Map<String, Map<String, String>>> allConfigs = new ArrayList<Map<String, Map<String, String>>>();
    allConfigs.add(clusterTopology.getConfiguration().getFullProperties());
    for (HostGroupInfo groupInfo : clusterTopology.getHostGroupInfo().values()) {
      // don't use full properties, only the properties specified in the host group config
      allConfigs.add(groupInfo.getConfiguration().getProperties());
    }

    for (Map<String, Map<String, String>> properties : allConfigs) {
      doSingleHostExportUpdate(singleHostTopologyUpdaters, properties);
      doSingleHostExportUpdate(dbHostTopologyUpdaters, properties);

      doMultiHostExportUpdate(multiHostTopologyUpdaters, properties);

      doRemovePropertyExport(removePropertyUpdaters, properties);

      doFilterPriorToExport(properties);
    }
  }

  /**
   * This method iterates over the properties passed in, and applies a
   * list of filters to the properties.
   *
   * If any filter implementations indicate that the property should
   * not be included in a collection (a Blueprint export in this case),
   * then the property is removed prior to the export.
   *
   *
   * @param properties config properties to process for filtering
   */
  private static void doFilterPriorToExport(Map<String, Map<String, String>> properties) {
    for (String configType : properties.keySet()) {
      Map<String, String> configPropertiesPerType =
        properties.get(configType);

      Set<String> propertiesToExclude = new HashSet<String>();
      for (String propertyName : configPropertiesPerType.keySet()) {
        if (shouldPropertyBeExcluded(propertyName, configPropertiesPerType.get(propertyName))) {
          propertiesToExclude.add(propertyName);
        }
      }

      if (!propertiesToExclude.isEmpty()) {
        for (String propertyName : propertiesToExclude) {
          configPropertiesPerType.remove(propertyName);
        }
      }
    }
  }

  /**
   * Creates a Collection of PropertyUpdater maps that will handle the configuration
   *   update for this cluster.
   *
   *   If NameNode HA is enabled, then updater instances will be added to the
   *   collection, in addition to the default list of Updaters that are statically defined.
   *
   *   Similarly, if Yarn ResourceManager HA is enabled, then updater instances specific
   *   to Yarn HA will be added to the default list of Updaters that are statically defined.
   *
   * @return Collection of PropertyUpdater maps used to handle cluster config update
   */
  private Collection<Map<String, Map<String, PropertyUpdater>>> createCollectionOfUpdaters() {
    Collection<Map<String, Map<String, PropertyUpdater>>> updaters = allUpdaters;

    if (clusterTopology.isNameNodeHAEnabled()) {
      updaters = addNameNodeHAUpdaters(updaters);
    }

    if (clusterTopology.isYarnResourceManagerHAEnabled()) {
      updaters = addYarnResourceManagerHAUpdaters(updaters);
    }

    if (isOozieServerHAEnabled(clusterTopology.getConfiguration().getFullProperties())) {
      updaters = addOozieServerHAUpdaters(updaters);
    }

    return updaters;
  }

  /**
   * Creates a Collection of PropertyUpdater maps that include the NameNode HA properties, and
   *   adds these to the list of updaters used to process the cluster configuration.  The HA
   *   properties are based on the names of the HA namservices and name nodes, and so must
   *   be registered at runtime, rather than in the static list.  This new Collection includes
   *   the statically-defined updaters, in addition to the HA-related updaters.
   *
   * @param updaters a Collection of updater maps to be included in the list of updaters for
   *                   this cluster config update
   * @return A Collection of PropertyUpdater maps to handle the cluster config update
   */
  private Collection<Map<String, Map<String, PropertyUpdater>>> addNameNodeHAUpdaters(Collection<Map<String, Map<String, PropertyUpdater>>> updaters) {
    Collection<Map<String, Map<String, PropertyUpdater>>> highAvailabilityUpdaters =
      new LinkedList<Map<String, Map<String, PropertyUpdater>>>();

    // always add the statically-defined list of updaters to the list to use
    // in processing cluster configuration
    highAvailabilityUpdaters.addAll(updaters);

    // add the updaters for the dynamic HA properties, based on the HA config in hdfs-site
    highAvailabilityUpdaters.add(createMapOfNameNodeHAUpdaters());

    return highAvailabilityUpdaters;
  }

  /**
   * Creates a Collection of PropertyUpdater maps that include the Yarn ResourceManager HA properties, and
   *   adds these to the list of updaters used to process the cluster configuration.  The HA
   *   properties are based on the names of the Resource Manager instances defined in
   *   yarn-site, and so must be registered at runtime, rather than in the static list.
   *
   *   This new Collection includes the statically-defined updaters,
   *   in addition to the HA-related updaters.
   *
   * @param updaters a Collection of updater maps to be included in the list of updaters for
   *                   this cluster config update
   * @return A Collection of PropertyUpdater maps to handle the cluster config update
   */
  private Collection<Map<String, Map<String, PropertyUpdater>>> addYarnResourceManagerHAUpdaters(Collection<Map<String, Map<String, PropertyUpdater>>> updaters) {
    Collection<Map<String, Map<String, PropertyUpdater>>> highAvailabilityUpdaters =
      new LinkedList<Map<String, Map<String, PropertyUpdater>>>();

    // always add the statically-defined list of updaters to the list to use
    // in processing cluster configuration
    highAvailabilityUpdaters.addAll(updaters);

    // add the updaters for the dynamic HA properties, based on the HA config in hdfs-site
    highAvailabilityUpdaters.add(createMapOfYarnResourceManagerHAUpdaters());

    return highAvailabilityUpdaters;
  }


  /**
   * Creates a Collection of PropertyUpdater maps that include the OozieServer HA properties, and
   *   adds these to the list of updaters used to process the cluster configuration.

   *   This new Collection includes the statically-defined updaters,
   *   in addition to the HA-related updaters.
   *
   * @param updaters a Collection of updater maps to be included in the list of updaters for
   *                   this cluster config update
   * @return A Collection of PropertyUpdater maps to handle the cluster config update
   */
  private Collection<Map<String, Map<String, PropertyUpdater>>> addOozieServerHAUpdaters(Collection<Map<String, Map<String, PropertyUpdater>>> updaters) {
    Collection<Map<String, Map<String, PropertyUpdater>>> highAvailabilityUpdaters =
      new LinkedList<Map<String, Map<String, PropertyUpdater>>>();

    // always add the statically-defined list of updaters to the list to use
    // in processing cluster configuration
    highAvailabilityUpdaters.addAll(updaters);

    // add the updaters for the Oozie HA properties not defined in stack, but
    // required to be present/updated in oozie-site
    highAvailabilityUpdaters.add(createMapOfOozieServerHAUpdaters());

    return highAvailabilityUpdaters;
  }

  /**
   * Performs export update for the set of properties that do not
   * require update during cluster setup, but should be removed
   * during a Blueprint export.
   *
   * In the case of a service referring to an external DB, any
   * properties that contain external host information should
   * be removed from the configuration that will be available in
   * the exported Blueprint.
   *
   * @param updaters set of updaters for properties that should
   *                 always be removed during a Blueprint export
   */
  private void doRemovePropertyExport(Map<String, Map<String, PropertyUpdater>> updaters,
                                      Map<String, Map<String, String>> properties) {

    for (Map.Entry<String, Map<String, PropertyUpdater>> entry : updaters.entrySet()) {
      String type = entry.getKey();
      for (String propertyName : entry.getValue().keySet()) {
        Map<String, String> typeProperties = properties.get(type);
        if ( (typeProperties != null) && (typeProperties.containsKey(propertyName)) ) {
          typeProperties.remove(propertyName);
        }
      }
    }
  }

  /**
   * Perform export update processing for HA configuration for NameNodes.  The HA NameNode property
   *   names are based on the nameservices defined when HA is enabled via the Ambari UI, so this method
   *   dynamically determines the property names, and registers PropertyUpdaters to handle the masking of
   *   host names in these configuration items.
   *
   */
  public void doNameNodeHAUpdate() {
    Map<String, Map<String, PropertyUpdater>> highAvailabilityUpdaters = createMapOfNameNodeHAUpdaters();

    // perform a single host update on these dynamically generated property names
    if (highAvailabilityUpdaters.get("hdfs-site").size() > 0) {
      doSingleHostExportUpdate(highAvailabilityUpdaters, clusterTopology.getConfiguration().getFullProperties());
    }
  }

  /**
   * Perform export update processing for HA configuration for Yarn ResourceManagers.  The HA ResourceManager
   * property names are based on the ResourceManager names defined when HA is enabled via the Ambari UI, so this method
   * dynamically determines the property names, and registers PropertyUpdaters to handle the masking of
   * host names in these configuration items.
   *
   */
  public void doYarnResourceManagerHAUpdate() {
    Map<String, Map<String, PropertyUpdater>> highAvailabilityUpdaters = createMapOfYarnResourceManagerHAUpdaters();

    // perform a single host update on these dynamically generated property names
    if (highAvailabilityUpdaters.get("yarn-site").size() > 0) {
      doSingleHostExportUpdate(highAvailabilityUpdaters, clusterTopology.getConfiguration().getFullProperties());
    }
  }

  /**
   * Perform export update processing for HA configuration for Oozie servers.  The properties used
   * in Oozie HA are not defined in the stack, but need to be added at runtime during an HA
   * deployment in order to support exporting/redeploying clusters with Oozie HA config.
   *
   */
  public void doOozieServerHAUpdate() {
    Map<String, Map<String, PropertyUpdater>> highAvailabilityUpdaters = createMapOfOozieServerHAUpdaters();

    if (highAvailabilityUpdaters.get("oozie-site").size() > 0) {
      doMultiHostExportUpdate(highAvailabilityUpdaters, clusterTopology.getConfiguration().getFullProperties());
    }
  }


  /**
   * Creates map of PropertyUpdater instances that are associated with
   *   NameNode High Availability (HA).  The HA configuration property
   *   names are dynamic, and based on other HA config elements in
   *   hdfs-site.  This method registers updaters for the required
   *   properties associated with each nameservice and namenode.
   *
   * @return a Map of registered PropertyUpdaters for handling HA properties in hdfs-site
   */
  private Map<String, Map<String, PropertyUpdater>> createMapOfNameNodeHAUpdaters() {
    Map<String, Map<String, PropertyUpdater>> highAvailabilityUpdaters = new HashMap<String, Map<String, PropertyUpdater>>();
    Map<String, PropertyUpdater> hdfsSiteUpdatersForAvailability = new HashMap<String, PropertyUpdater>();
    highAvailabilityUpdaters.put("hdfs-site", hdfsSiteUpdatersForAvailability);

    //todo: Do we need to call this for HG configurations?
    Map<String, String> hdfsSiteConfig = clusterTopology.getConfiguration().getFullProperties().get("hdfs-site");
    // generate the property names based on the current HA config for the NameNode deployments
    for (String nameService : parseNameServices(hdfsSiteConfig)) {
      for (String nameNode : parseNameNodes(nameService, hdfsSiteConfig)) {
        final String httpsPropertyName = "dfs.namenode.https-address." + nameService + "." + nameNode;
        hdfsSiteUpdatersForAvailability.put(httpsPropertyName, new SingleHostTopologyUpdater("NAMENODE"));
        final String httpPropertyName = "dfs.namenode.http-address." + nameService + "." + nameNode;
        hdfsSiteUpdatersForAvailability.put(httpPropertyName, new SingleHostTopologyUpdater("NAMENODE"));
        final String rpcPropertyName = "dfs.namenode.rpc-address." + nameService + "." + nameNode;
        hdfsSiteUpdatersForAvailability.put(rpcPropertyName, new SingleHostTopologyUpdater("NAMENODE"));
      }
    }
    return highAvailabilityUpdaters;
  }


  /**
   * Creates map of PropertyUpdater instances that are associated with
   *   Yarn ResourceManager High Availability (HA).  The HA configuration property
   *   names are dynamic, and based on other HA config elements in
   *   yarn-site.  This method registers updaters for the required
   *   properties associated with each ResourceManager.
   *
   * @return a Map of registered PropertyUpdaters for handling HA properties in yarn-site
   */
  private Map<String, Map<String, PropertyUpdater>> createMapOfYarnResourceManagerHAUpdaters() {
    Map<String, Map<String, PropertyUpdater>> highAvailabilityUpdaters = new HashMap<String, Map<String, PropertyUpdater>>();
    Map<String, PropertyUpdater> yarnSiteUpdatersForAvailability = new HashMap<String, PropertyUpdater>();
    highAvailabilityUpdaters.put("yarn-site", yarnSiteUpdatersForAvailability);

    Map<String, String> yarnSiteConfig = clusterTopology.getConfiguration().getFullProperties().get("yarn-site");
    // generate the property names based on the current HA config for the ResourceManager deployments
    for (String resourceManager : parseResourceManagers(yarnSiteConfig)) {
      final String rmHostPropertyName = "yarn.resourcemanager.hostname." + resourceManager;
      yarnSiteUpdatersForAvailability.put(rmHostPropertyName, new SingleHostTopologyUpdater("RESOURCEMANAGER"));

      final String rmHTTPAddress = "yarn.resourcemanager.webapp.address." + resourceManager;
      yarnSiteUpdatersForAvailability.put(rmHTTPAddress, new SingleHostTopologyUpdater("RESOURCEMANAGER"));

      final String rmHTTPSAddress = "yarn.resourcemanager.webapp.https.address." + resourceManager;
      yarnSiteUpdatersForAvailability.put(rmHTTPSAddress, new SingleHostTopologyUpdater("RESOURCEMANAGER"));
    }

    return highAvailabilityUpdaters;
  }

  /**
   * Creates map of PropertyUpdater instances that are associated with
   *   Oozie Server High Availability (HA).
   *
   * @return a Map of registered PropertyUpdaters for handling HA properties in oozie-site
   */
  private Map<String, Map<String, PropertyUpdater>> createMapOfOozieServerHAUpdaters() {
    Map<String, Map<String, PropertyUpdater>> highAvailabilityUpdaters = new HashMap<String, Map<String, PropertyUpdater>>();
    Map<String, PropertyUpdater> oozieSiteUpdatersForAvailability = new HashMap<String, PropertyUpdater>();
    highAvailabilityUpdaters.put("oozie-site", oozieSiteUpdatersForAvailability);

    // register a multi-host property updater for this Oozie property.
    // this property is not defined in the stacks, since HA is not supported yet
    // by the stack definition syntax.  This property should only be considered in
    // an Oozie HA cluster.
    oozieSiteUpdatersForAvailability.put("oozie.zookeeper.connection.string", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));

    return highAvailabilityUpdaters;

  }

  /**
   * Static convenience function to determine if Oozie HA is enabled
   * @param configProperties configuration properties for this cluster
   * @return true if Oozie HA is enabled
   *         false if Oozie HA is not enabled
   */
  static boolean isOozieServerHAEnabled(Map<String, Map<String, String>> configProperties) {
    return configProperties.containsKey("oozie-site") && configProperties.get("oozie-site").containsKey("oozie.services.ext")
      && configProperties.get("oozie-site").get("oozie.services.ext").contains("org.apache.oozie.service.ZKLocksService");
  }

  /**
   * Static convenience function to determine if HiveServer HA is enabled
   * @param configProperties configuration properties for this cluster
   * @return true if HiveServer HA is enabled
   *         false if HiveServer HA is not enabled
   */
  static boolean isHiveServerHAEnabled(Map<String, Map<String, String>> configProperties) {
    return configProperties.containsKey("hive-site") && configProperties.get("hive-site").containsKey("hive.server2.support.dynamic.service.discovery")
      && configProperties.get("hive-site").get("hive.server2.support.dynamic.service.discovery").equals("true");
  }

  /**
   * Convenience method to examine the current configuration, to determine
   * if the hostname of the initial active namenode in an HA deployment has
   * been included.
   *
   * @param configProperties the configuration for this cluster
   * @return true if the initial active namenode property has been configured
   *         false if the initial active namenode property has not been configured
   */
  static boolean isNameNodeHAInitialActiveNodeSet(Map<String, Map<String, String>> configProperties) {
    return configProperties.containsKey("hadoop-env") && configProperties.get("hadoop-env").containsKey("dfs_ha_initial_namenode_active");
  }


  /**
   * Convenience method to examine the current configuration, to determine
   * if the hostname of the initial standby namenode in an HA deployment has
   * been included.
   *
   * @param configProperties the configuration for this cluster
   * @return true if the initial standby namenode property has been configured
   *         false if the initial standby namenode property has not been configured
   */
  static boolean isNameNodeHAInitialStandbyNodeSet(Map<String, Map<String, String>> configProperties) {
    return configProperties.containsKey("hadoop-env") && configProperties.get("hadoop-env").containsKey("dfs_ha_initial_namenode_standby");
  }


  /**
   * Parses out the list of nameservices associated with this HDFS configuration.
   *
   * @param properties config properties for this cluster
   *
   * @return array of Strings that indicate the nameservices for this cluster
   */
  static String[] parseNameServices(Map<String, String> properties) {
    final String nameServices = properties.get("dfs.nameservices");
    return splitAndTrimStrings(nameServices);
  }

  /**
   * Parses out the list of resource managers associated with this yarn-site configuration.
   *
   * @param properties config properties for this cluster
   *
   * @return array of Strings that indicate the ResourceManager names for this HA cluster
   */
  static String[] parseResourceManagers(Map<String, String> properties) {
    final String resourceManagerNames = properties.get("yarn.resourcemanager.ha.rm-ids");
    return splitAndTrimStrings(resourceManagerNames);
  }

  /**
   * Parses out the list of name nodes associated with a given HDFS
   *   NameService, based on a given HDFS configuration.
   *
   * @param nameService the nameservice used for this parsing
   * @param properties config properties for this cluster
   *
   * @return array of Strings that indicate the name nodes associated
   *           with this nameservice
   */
  static String[] parseNameNodes(String nameService, Map<String, String> properties) {
    final String nameNodes = properties.get("dfs.ha.namenodes." + nameService);
    return splitAndTrimStrings(nameNodes);
  }

  /**
   * Iterates over the list of registered filters for this config processor, and
   * queries each filter to determine if a given property should be included
   * in a property collection.  If any filters return false for the isPropertyIncluded()
   * query, then the property should be excluded.
   *
   * @param propertyName config property name
   * @param propertyValue config property value
   * @return true if the property should be excluded
   *         false if the property should not be excluded
   */
  private static boolean shouldPropertyBeExcluded(String propertyName, String propertyValue) {
    for(PropertyFilter filter : propertyFilters) {
      if (!filter.isPropertyIncluded(propertyName, propertyValue)) {
        return true;
      }
    }

    // if no filters require that the property be excluded,
    // then allow it to be included in the property collection
    return false;
  }

  /**
   * Update single host topology configuration properties for blueprint export.
   *
   * @param updaters    registered updaters
   */
  private void doSingleHostExportUpdate(Map<String, Map<String, PropertyUpdater>> updaters, Map<String, Map<String, String>> properties) {

    for (Map.Entry<String, Map<String, PropertyUpdater>> entry : updaters.entrySet()) {
      String type = entry.getKey();
      for (String propertyName : entry.getValue().keySet()) {
        boolean matchedHost = false;

        Map<String, String> typeProperties = properties.get(type);
        if (typeProperties != null && typeProperties.containsKey(propertyName)) {
          String propValue = typeProperties.get(propertyName);

          for (HostGroupInfo groupInfo : clusterTopology.getHostGroupInfo().values()) {
            Collection<String> hosts = groupInfo.getHostNames();
            for (String host : hosts) {
              //todo: need to use regular expression to avoid matching a host which is a superset.
              if (propValue.contains(host)) {
                matchedHost = true;
                typeProperties.put(propertyName, propValue.replace(
                    host, "%HOSTGROUP::" + groupInfo.getHostGroupName() + "%"));
                break;
              }
            }
            if (matchedHost) {
              break;
            }
          }
          // remove properties that do not contain hostnames,
          // except in the case of HA-related properties, that
          // can contain nameservice references instead of hostnames (Fix for Bug AMBARI-7458).
          // also will not remove properties that reference the special 0.0.0.0 network
          // address or properties with undefined hosts
          if (! matchedHost &&
              ! isNameServiceProperty(propertyName) &&
              ! isSpecialNetworkAddress(propValue)  &&
              ! isUndefinedAddress(propValue)) {

            typeProperties.remove(propertyName);
          }
        }
      }
    }
  }

  /**
   * Determines if a given property name's value can include
   *   nameservice references instead of host names.
   *
   * @param propertyName name of the property
   *
   * @return true if this property can support using nameservice names
   *         false if this property cannot support using nameservice names
   */
  private static boolean isNameServiceProperty(String propertyName) {
    return configPropertiesWithHASupport.contains(propertyName);
  }

  /**
   * Queries a property value to determine if the value contains
   *   a host address with all zeros (0.0.0.0).  This is a special
   *   address that signifies that the service is available on
   *   all network interfaces on a given machine.
   *
   * @param propertyValue the property value to inspect
   *
   * @return true if the 0.0.0.0 address is included in this string
   *         false if the 0.0.0.0 address is not included in this string
   */
  private static boolean isSpecialNetworkAddress(String propertyValue) {
    return propertyValue.contains("0.0.0.0");
  }

  /**
   * Determine if a property has an undefined host.
   *
   * @param propertyValue  property value
   *
   * @return true if the property value contains "undefined"
   */
  private static boolean isUndefinedAddress(String propertyValue) {
    return propertyValue.contains("undefined");
  }

  /**
   * Update multi host topology configuration properties for blueprint export.
   *
   * @param updaters    registered updaters
   */
  private void doMultiHostExportUpdate(Map<String, Map<String, PropertyUpdater>> updaters, Map<String, Map<String, String>> properties) {
    for (Map.Entry<String, Map<String, PropertyUpdater>> entry : updaters.entrySet()) {
      String type = entry.getKey();
      for (String propertyName : entry.getValue().keySet()) {
        Map<String, String> typeProperties = properties.get(type);
        if (typeProperties != null && typeProperties.containsKey(propertyName)) {
          String propValue = typeProperties.get(propertyName);
          for (HostGroupInfo groupInfo : clusterTopology.getHostGroupInfo().values()) {
            Collection<String> hosts = groupInfo.getHostNames();
            for (String host : hosts) {
              propValue = propValue.replaceAll(host + "\\b", "%HOSTGROUP::" +
                  groupInfo.getHostGroupName() + "%");
            }
          }
          Collection<String> addedGroups = new HashSet<String>();
          String[] toks = propValue.split(",");
          boolean inBrackets = propValue.startsWith("[");

          StringBuilder sb = new StringBuilder();
          if (inBrackets) {
            sb.append('[');
          }
          boolean firstTok = true;
          for (String tok : toks) {
            tok = tok.replaceAll("[\\[\\]]", "");

            if (addedGroups.add(tok)) {
              if (! firstTok) {
                sb.append(',');
              }
              sb.append(tok);
            }
            firstTok = false;
          }

          if (inBrackets) {
            sb.append(']');
          }
          typeProperties.put(propertyName, sb.toString());
        }
      }
    }
  }

  /**
   * Convert a property value which includes a host group topology token to a physical host.
   *
   *
   * @param val       value to be converted
   * @param topology  cluster topology
   *
   * @return updated value with physical host name
   */
  //todo: replace this with parseHostGroupToken which would return a hostgroup or null
  private static Collection<String> getHostStrings(String val, ClusterTopology topology) {

    Collection<String> hosts = new LinkedHashSet<String>();
    Matcher m = HOSTGROUP_PORT_REGEX.matcher(val);
    while (m.find()) {
      String groupName = m.group(1);
      String port = m.group(2);

      HostGroupInfo hostGroupInfo = topology.getHostGroupInfo().get(groupName);
      if (hostGroupInfo == null) {
        throw new IllegalArgumentException(
            "Unable to match blueprint host group token to a host group: " + groupName);
      }

      for (String host : hostGroupInfo.getHostNames()) {
        if (port != null) {
          host += ":" + port;
        }
        hosts.add(host);
      }
    }
    return hosts;
  }

  /**
   * Convenience method for splitting out the HA-related properties, while
   *   also removing leading/trailing whitespace.
   *
   * @param propertyName property name to parse
   *
   * @return an array of Strings that represent the comma-separated
   *         elements in this property
   */
  private static String[] splitAndTrimStrings(String propertyName) {
    List<String> namesWithoutWhitespace = new LinkedList<String>();
    for (String service : propertyName.split(",")) {
      namesWithoutWhitespace.add(service.trim());
    }

    return namesWithoutWhitespace.toArray(new String[namesWithoutWhitespace.size()]);
  }

  /**
   * Provides functionality to update a property value.
   */
  public interface PropertyUpdater {
    /**
     * Update a property value.
     *
     * @param propertyName    name of
     * @param origValue       original value of property
     * @param properties      all properties
     * @param topology        cluster topology
     *
     * @return new property value
     */
    String updateForClusterCreate(String propertyName,
                                         String origValue,
                                         Map<String, Map<String, String>> properties,
                                         ClusterTopology topology);

    Collection<String> getRequiredHostGroups(String origValue,
                                                    Map<String, Map<String, String>> properties,
                                                    ClusterTopology topology);
  }

  /**
   * Topology based updater which replaces the original host name of a property with the host name
   * which runs the associated (master) component in the new cluster.
   */
  private static class SingleHostTopologyUpdater implements PropertyUpdater {
    /**
     * Component name
     */
    private String component;

    /**
     * Constructor.
     *
     * @param component  component name associated with the property
     */
    public SingleHostTopologyUpdater(String component) {
      this.component = component;
    }

    /**
     * Update the property with the new host name which runs the associated component.
     *
     * @param propertyName  name of property
     * @param origValue     original value of property
     * @param properties    all properties
     * @param topology      cluster topology
     *
     * @return updated property value with old host name replaced by new host name
     */
    @Override
    public String updateForClusterCreate(String propertyName,
                                         String origValue,
                                         Map<String, Map<String, String>> properties,
                                         ClusterTopology topology)  {

      //todo: getHostStrings
      Matcher m = HOSTGROUP_REGEX.matcher(origValue);
      if (m.find()) {
        String hostGroupName = m.group(1);

        HostGroupInfo groupInfo = topology.getHostGroupInfo().get(hostGroupName);
        if (groupInfo == null) {
          //todo: this should be validated in configuration validation
          throw new RuntimeException(
              "Encountered a host group token in configuration which couldn't be matched to a host group: "
              + hostGroupName);
        }

        //todo: warn if > hosts
        return origValue.replace(m.group(0), groupInfo.getHostNames().iterator().next());
      } else {
        int matchingGroupCount = topology.getHostGroupsForComponent(component).size();
        if (matchingGroupCount == 1) {
          Collection<String> componentHosts = topology.getHostAssignmentsForComponent(component);
          //todo: warn if > 1 hosts
          return origValue.replace("localhost", componentHosts.iterator().next());
        } else {
          //todo: extract all hard coded HA logic
          Cardinality cardinality = topology.getBlueprint().getStack().getCardinality(component);
          // if no matching host groups are found for a component whose configuration
          // is handled by this updater, check the stack first to determine if
          // zero is a valid cardinality for this component.  This is necessary
          // in the case of a component in "technical preview" status, since it
          // may be valid to have 0 or 1 instances of such a component in the cluster
          if (matchingGroupCount == 0 && cardinality.isValidCount(0)) {
            return origValue;
          } else {
            if (topology.isNameNodeHAEnabled() && isComponentNameNode() && (matchingGroupCount == 2)) {
              // if this is the defaultFS property, it should reflect the nameservice name,
              // rather than a hostname (used in non-HA scenarios)
              if (properties.containsKey("core-site") && properties.get("core-site").get("fs.defaultFS").equals(origValue)) {
                return origValue;
              }

              if (properties.containsKey("hbase-site") && properties.get("hbase-site").get("hbase.rootdir").equals(origValue)) {
                // hbase-site's reference to the namenode is handled differently in HA mode, since the
                // reference must point to the logical nameservice, rather than an individual namenode
                return origValue;
              }

              if (properties.containsKey("accumulo-site") && properties.get("accumulo-site").get("instance.volumes").equals(origValue)) {
                // accumulo-site's reference to the namenode is handled differently in HA mode, since the
                // reference must point to the logical nameservice, rather than an individual namenode
                return origValue;
              }

              if (!origValue.contains("localhost")) {
                // if this NameNode HA property is a FDQN, then simply return it
                return origValue;
              }

            }

            if (topology.isNameNodeHAEnabled() && isComponentSecondaryNameNode() && (matchingGroupCount == 0)) {
              // if HDFS HA is enabled, then no replacement is necessary for properties that refer to the SECONDARY_NAMENODE
              // eventually this type of information should be encoded in the stacks
              return origValue;
            }

            if (topology.isYarnResourceManagerHAEnabled() && isComponentResourceManager() && (matchingGroupCount == 2)) {
              if (!origValue.contains("localhost")) {
                // if this Yarn property is a FQDN, then simply return it
                return origValue;
              }
            }

            if ((isOozieServerHAEnabled(properties)) && isComponentOozieServer() && (matchingGroupCount > 1))     {
              if (!origValue.contains("localhost")) {
                // if this Oozie property is a FQDN, then simply return it
                return origValue;
              }
            }

            if ((isHiveServerHAEnabled(properties)) && isComponentHiveServer() && (matchingGroupCount > 1)) {
              if (!origValue.contains("localhost")) {
                // if this Hive property is a FQDN, then simply return it
                return origValue;
              }
            }

            if ((isComponentHiveMetaStoreServer()) && matchingGroupCount > 1) {
              if (!origValue.contains("localhost")) {
                // if this Hive MetaStore property is a FQDN, then simply return it
                return origValue;
              }
            }


            throw new IllegalArgumentException("Unable to update configuration property " + "'" + propertyName + "'"+ " with topology information. " +
              "Component '" + component + "' is not mapped to any host group or is mapped to multiple groups.");
          }
        }
      }
    }

    @Override
    public Collection<String> getRequiredHostGroups(String origValue,
                                                    Map<String, Map<String, String>> properties,
                                                    ClusterTopology topology) {
      //todo: getHostStrings
      Matcher m = HOSTGROUP_REGEX.matcher(origValue);
      if (m.find()) {
        String hostGroupName = m.group(1);
        return Collections.singleton(hostGroupName);
      } else {
        Collection<String> matchingGroups = topology.getHostGroupsForComponent(component);
        int matchingGroupCount = matchingGroups.size();
        if (matchingGroupCount == 1) {
          return Collections.singleton(matchingGroups.iterator().next());
        } else {
          Cardinality cardinality = topology.getBlueprint().getStack().getCardinality(component);
          // if no matching host groups are found for a component whose configuration
          // is handled by this updater, return an empty set
          if (matchingGroupCount == 0 && cardinality.isValidCount(0)) {
            return Collections.emptySet();
          } else {
            //todo: shouldn't have all of these hard coded HA rules here
            if (topology.isNameNodeHAEnabled() && isComponentNameNode() && (matchingGroupCount == 2)) {
              // if this is the defaultFS property, it should reflect the nameservice name,
              // rather than a hostname (used in non-HA scenarios)
              if (properties.containsKey("core-site") && properties.get("core-site").get("fs.defaultFS").equals(origValue)) {
                return Collections.emptySet();
              }

              if (properties.containsKey("hbase-site") && properties.get("hbase-site").get("hbase.rootdir").equals(origValue)) {
                // hbase-site's reference to the namenode is handled differently in HA mode, since the
                // reference must point to the logical nameservice, rather than an individual namenode
                return Collections.emptySet();
              }

              if (properties.containsKey("accumulo-site") && properties.get("accumulo-site").get("instance.volumes").equals(origValue)) {
                // accumulo-site's reference to the namenode is handled differently in HA mode, since the
                // reference must point to the logical nameservice, rather than an individual namenode
                return Collections.emptySet();
              }

              if (!origValue.contains("localhost")) {
                // if this NameNode HA property is a FDQN, then simply return it
                return Collections.emptySet();
              }
            }

            if (topology.isNameNodeHAEnabled() && isComponentSecondaryNameNode() && (matchingGroupCount == 0)) {
              // if HDFS HA is enabled, then no replacement is necessary for properties that refer to the SECONDARY_NAMENODE
              // eventually this type of information should be encoded in the stacks
              return Collections.emptySet();
            }

            if (topology.isYarnResourceManagerHAEnabled() && isComponentResourceManager() && (matchingGroupCount == 2)) {
              if (!origValue.contains("localhost")) {
                // if this Yarn property is a FQDN, then simply return it
                return Collections.emptySet();
              }
            }

            if ((isOozieServerHAEnabled(properties)) && isComponentOozieServer() && (matchingGroupCount > 1)) {
              if (!origValue.contains("localhost")) {
                // if this Oozie property is a FQDN, then simply return it
                return Collections.emptySet();
              }
            }

            if ((isHiveServerHAEnabled(properties)) && isComponentHiveServer() && (matchingGroupCount > 1)) {
              if (!origValue.contains("localhost")) {
                // if this Hive property is a FQDN, then simply return it
                return Collections.emptySet();
              }
            }

            if ((isComponentHiveMetaStoreServer()) && matchingGroupCount > 1) {
              if (!origValue.contains("localhost")) {
                // if this Hive MetaStore property is a FQDN, then simply return it
                return Collections.emptySet();
              }
            }
            //todo: property name
            throw new IllegalArgumentException("Unable to update configuration property with topology information. " +
                "Component '" + component + "' is not mapped to any host group or is mapped to multiple groups.");
          }
        }
      }
    }

    /**
     * Utility method to determine if the component associated with this updater
     * instance is an HDFS NameNode
     *
     * @return true if the component associated is a NameNode
     *         false if the component is not a NameNode
     */
    private boolean isComponentNameNode() {
      return component.equals("NAMENODE");
    }

    /**
     * Utility method to determine if the component associated with this updater
     * instance is an HDFS Secondary NameNode
     *
     * @return true if the component associated is a Secondary NameNode
     *         false if the component is not a Secondary NameNode
     */
    private boolean isComponentSecondaryNameNode() {
      return component.equals("SECONDARY_NAMENODE");
    }

    /**
     * Utility method to determine if the component associated with this updater
     * instance is a Yarn ResourceManager
     *
     * @return true if the component associated is a Yarn ResourceManager
     *         false if the component is not a Yarn ResourceManager
     */
    private boolean isComponentResourceManager() {
      return component.equals("RESOURCEMANAGER");
    }

    /**
     * Utility method to determine if the component associated with this updater
     * instance is an Oozie Server
     *
     * @return true if the component associated is an Oozie Server
     *         false if the component is not an Oozie Server
     */
    private boolean isComponentOozieServer() {
      return component.equals("OOZIE_SERVER");
    }

    /**
     * Utility method to determine if the component associated with this updater
     * instance is a Hive Server
     *
     * @return true if the component associated is a Hive Server
     *         false if the component is not a Hive Server
     */
    private boolean isComponentHiveServer() {
      return component.equals("HIVE_SERVER");
    }

    /**
     * Utility method to determine if the component associated with this updater
     * instance is a Hive MetaStore Server
     *
     * @return true if the component associated is a Hive MetaStore Server
     *         false if the component is not a Hive MetaStore Server
     */
    private boolean isComponentHiveMetaStoreServer() {
      return component.equals("HIVE_METASTORE");
    }

    /**
     * Provides access to the name of the component associated
     *   with this updater instance.
     *
     * @return component name for this updater
     */
    public String getComponentName() {
      return component;
    }
  }

  /**
   * Extension of SingleHostTopologyUpdater that supports the
   * notion of an optional service.  An example: the Storm
   * service has config properties that require the location
   * of the Ganglia server when Ganglia is deployed, but Storm
   * should also start properly without Ganglia.
   *
   * This updater detects the case when the specified component
   * is not found, and returns the original property value.
   *
   */
  private static class OptionalSingleHostTopologyUpdater extends SingleHostTopologyUpdater {

    public OptionalSingleHostTopologyUpdater(String component) {
      super(component);
    }

    @Override
    public String updateForClusterCreate(String propertyName,
                                         String origValue,
                                         Map<String, Map<String, String>> properties,
                                         ClusterTopology topology) {
      try {
        return super.updateForClusterCreate(propertyName, origValue, properties, topology);
      } catch (IllegalArgumentException illegalArgumentException) {
        // return the original value, since the optional component is not available in this cluster
        return origValue;
      }
    }

    @Override
    public Collection<String> getRequiredHostGroups(String origValue,
                                                    Map<String, Map<String, String>> properties,
                                                    ClusterTopology topology) {

      try {
        return super.getRequiredHostGroups(origValue, properties, topology);
      } catch (IllegalArgumentException e) {
        return Collections.emptySet();
      }
    }
  }

  /**
   * Topology based updater which replaces the original host name of a database property with the host name
   * where the DB is deployed in the new cluster.  If an existing database is specified, the original property
   * value is returned.
   */
  private static class DBTopologyUpdater extends SingleHostTopologyUpdater {
    /**
     * Property type (global, core-site ...) for property which is used to determine if DB is external.
     */
    private final String configPropertyType;

    /**
     * Name of property which is used to determine if DB is new or existing (exernal).
     */
    private final String conditionalPropertyName;

    /**
     * Constructor.
     *
     * @param component                component to get hot name if new DB
     * @param conditionalPropertyType  config type of property used to determine if DB is external
     * @param conditionalPropertyName  name of property which is used to determine if DB is external
     */
    private DBTopologyUpdater(String component, String conditionalPropertyType,
                              String conditionalPropertyName) {
      super(component);
      configPropertyType = conditionalPropertyType;
      this.conditionalPropertyName = conditionalPropertyName;
    }

    /**
     * If database is a new managed database, update the property with the new host name which
     * runs the associated component.  If the database is external (non-managed), return the
     * original value.
     *
     * @param propertyName  property name
     * @param origValue     original value of property
     * @param properties    all properties
     * @param topology      cluster topology
     *
     * @return updated property value with old host name replaced by new host name or original value
     *         if the database is external
     */
    @Override

    public String updateForClusterCreate(String propertyName,
                                         String origValue,
                                         Map<String, Map<String, String>> properties,
                                         ClusterTopology topology) {

      if (isDatabaseManaged(properties)) {
        return super.updateForClusterCreate(propertyName, origValue, properties, topology);
      } else {
        return origValue;
      }
    }

    @Override
    public Collection<String> getRequiredHostGroups(String origValue, Map<String, Map<String, String>> properties, ClusterTopology topology) {
      if (isDatabaseManaged(properties)) {
        return super.getRequiredHostGroups(origValue, properties, topology);
      } else {
        return Collections.emptySet();
      }
    }

    /**
     * Determine if database is managed, meaning that it is a component in the cluster topology.
     *
     * @return true if the DB is managed; false otherwise
     */
    private boolean isDatabaseManaged(Map<String, Map<String, String>> properties) {
      // conditional property should always exist since it is required to be specified in the stack
      return properties.get(configPropertyType).
          get(conditionalPropertyName).startsWith("New");
    }
  }

  /**
   * Topology based updater which replaces original host names (possibly more than one) contained in a property
   * value with the host names which runs the associated component in the new cluster.
   */
  private static class MultipleHostTopologyUpdater implements PropertyUpdater {

    private static final Character DEFAULT_SEPARATOR = ',';

    /**
     * Component name
     */
    private final String component;

    /**
     * Separator for multiple property values
     */
    private final Character separator;

    /**
     * Flag to determine if a URL scheme detected as
     * a prefix in the property should be repeated across
     * all hosts in the property
     */
    private final boolean usePrefixForEachHost;

    private final Set<String> setOfKnownURLSchemes = Collections.singleton("thrift://");

    /**
     * Constructor.
     *
     * @param component  component name associated with the property
     */
    public MultipleHostTopologyUpdater(String component) {
      this(component, DEFAULT_SEPARATOR, false);
    }

    /**
     * Constructor
     *
     * @param component component name associated with this property
     * @param separator the separator character to use when multiple hosts
     *                  are specified in a property or URL
     */
    public MultipleHostTopologyUpdater(String component, Character separator, boolean userPrefixForEachHost) {
      this.component = component;
      this.separator = separator;
      this.usePrefixForEachHost = userPrefixForEachHost;
    }

    /**
     * Update all host names included in the original property value with new host names which run the associated
     * component.
     *
     * @param propertyName property name
     * @param origValue    original value of property
     * @param properties   all properties
     * @param topology     cluster topology
     * @return updated property value with old host names replaced by new host names
     */
    @Override
    public String updateForClusterCreate(String propertyName,
                                         String origValue,
                                         Map<String, Map<String, String>> properties,
                                         ClusterTopology topology) {

      StringBuilder sb = new StringBuilder();

      if (!origValue.contains("%HOSTGROUP") &&
          (!origValue.contains("localhost"))) {
        // this property must contain FQDNs specified directly by the user
        // of the Blueprint, so the processor should not attempt to update them
        return origValue;
      }

      String prefix = null;
      Collection<String> hostStrings = getHostStrings(origValue, topology);
      if (hostStrings.isEmpty()) {
        //default non-exported original value
        String port;
        for (String urlScheme : setOfKnownURLSchemes) {
          if (origValue.startsWith(urlScheme)) {
            prefix = urlScheme;
          }
        }

        if (prefix != null) {
          String valueWithoutPrefix = origValue.substring(prefix.length());
          port = calculatePort(valueWithoutPrefix);
          sb.append(prefix);
        } else {
          port = calculatePort(origValue);
        }

        for (String host : topology.getHostAssignmentsForComponent(component)) {
          if (port != null) {
            host += ":" + port;
          }
          hostStrings.add(host);
        }
      }

      String suffix = null;
      // parse out prefix if one exists
      Matcher matcher = HOSTGROUP_PORT_REGEX.matcher(origValue);
      if (matcher.find()) {
        int indexOfStart = matcher.start();
        // handle the case of a YAML config property
        if ((indexOfStart > 0) && (!origValue.substring(0, indexOfStart).equals("['"))) {
          // append prefix before adding host names
          prefix = origValue.substring(0, indexOfStart);
          sb.append(prefix);
        }

        // parse out suffix if one exists
        int indexOfEnd = -1;
        while (matcher.find()) {
          indexOfEnd = matcher.end();
        }

        if ((indexOfEnd > -1) && (indexOfEnd < (origValue.length() - 1))) {
          suffix = origValue.substring(indexOfEnd);
        }
      }

      // add hosts to property, using the specified separator
      boolean firstHost = true;
      for (String host : hostStrings) {
        if (!firstHost) {
          sb.append(separator);
          // support config properties that use a list of full URIs
          if (usePrefixForEachHost && (prefix != null)) {
            sb.append(prefix);
          }
        } else {
          firstHost = false;
        }
        sb.append(host);
      }

      if ((suffix != null) && (!suffix.equals("']"))) {
        sb.append(suffix);
      }
      return sb.toString();
    }

    private static String calculatePort(String origValue) {
      if (origValue.contains(":")) {
        //todo: currently assuming all hosts are using same port
        return origValue.substring(origValue.indexOf(":") + 1);
      }

      return null;
    }

    @Override
    public Collection<String> getRequiredHostGroups(String origValue,
                                                    Map<String, Map<String, String>> properties,
                                                    ClusterTopology topology) {

      Collection<String> requiredHostGroups = new HashSet<String>();

      // add all host groups specified in host group tokens
      Matcher m = HOSTGROUP_PORT_REGEX.matcher(origValue);
      while (m.find()) {
        String groupName = m.group(1);

        if (!topology.getBlueprint().getHostGroups().containsKey(groupName)) {
          throw new IllegalArgumentException(
              "Unable to match blueprint host group token to a host group: " + groupName);
        }
        requiredHostGroups.add(groupName);
      }

      //todo: for now assuming that we will either have HG tokens or standard replacement but not both
      //todo: as is done in updateForClusterCreate
      if (requiredHostGroups.isEmpty()) {
        requiredHostGroups.addAll(topology.getHostGroupsForComponent(component));
      }

      return requiredHostGroups;
    }
  }

  /**
   * Updater which appends "m" to the original property value.
   * For example, "1024" would be updated to "1024m".
   */
  private static class MPropertyUpdater implements PropertyUpdater {
    /**
     * Append 'm' to the original property value if it doesn't already exist.
     *
     * @param propertyName  property name
     * @param origValue     original value of property
     * @param properties    all properties
     * @param topology      cluster topology
     *
     * @return property with 'm' appended
     */
    @Override
    public String updateForClusterCreate(String propertyName,
                                         String origValue,
                                         Map<String, Map<String, String>> properties,
                                         ClusterTopology topology) {

      return origValue.endsWith("m") ? origValue : origValue + 'm';
    }

    @Override
    public Collection<String> getRequiredHostGroups(String origValue,
                                                    Map<String, Map<String, String>> properties, ClusterTopology topology) {
      return Collections.emptySet();
    }
  }

  /**
   * Class to facilitate special formatting needs of property values.
   */
  private abstract static class AbstractPropertyValueDecorator implements PropertyUpdater {
    PropertyUpdater propertyUpdater;

    /**
     * Constructor.
     *
     * @param propertyUpdater  wrapped updater
     */
    public AbstractPropertyValueDecorator(PropertyUpdater propertyUpdater) {
      this.propertyUpdater = propertyUpdater;
    }

    /**
     * Return decorated form of the updated input property value.
     *
     * @param propertyName  property name
     * @param origValue     original value of property
     * @param properties    all properties
     * @param topology      cluster topology
     *
     * @return Formatted output string
     */
    @Override
    public String updateForClusterCreate(String propertyName,
                                         String origValue,
                                         Map<String, Map<String, String>> properties,
                                         ClusterTopology topology) {

      // return customer-supplied properties without updating them
      if (isFQDNValue(origValue)) {
        return origValue;
      }

      return doFormat(propertyUpdater.updateForClusterCreate(propertyName, origValue, properties, topology));
    }

    /**
     * Transform input string to required output format.
     *
     * @param originalValue  original value of property
     *
     * @return formatted output string
     */
    public abstract String doFormat(String originalValue);

    @Override
    public Collection<String> getRequiredHostGroups(String origValue,
                                                    Map<String, Map<String, String>> properties, ClusterTopology topology) {

      return propertyUpdater.getRequiredHostGroups(origValue, properties, topology);
    }

    /**
     * Convenience method to determine if a property value is a
     * customer-specified FQDN.
     *
     * @param value property value to examine
     * @return true if the property represents an FQDN value
     *         false if the property does not represent an FQDN value
     */
    public boolean isFQDNValue(String value) {
      return !value.contains("%HOSTGROUP") &&
            !value.contains("localhost");
    }
  }

  /**
   * Return properties of the form ['value']
   */
  private static class YamlMultiValuePropertyDecorator extends AbstractPropertyValueDecorator {

    // currently, only plain and single-quoted Yaml flows are supported by this updater
    enum FlowStyle {
      SINGLE_QUOTED,
      PLAIN
    }

    private final FlowStyle flowStyle;

    public YamlMultiValuePropertyDecorator(PropertyUpdater propertyUpdater) {
      // single-quote style is considered default by this updater
      this(propertyUpdater, FlowStyle.SINGLE_QUOTED);
    }

    protected YamlMultiValuePropertyDecorator(PropertyUpdater propertyUpdater, FlowStyle flowStyle) {
      super(propertyUpdater);
      this.flowStyle = flowStyle;
    }

    /**
     * Format input String of the form, str1,str2 to ['str1','str2']
     *
     * @param origValue  input string
     *
     * @return formatted string
     */
    @Override
    public String doFormat(String origValue) {
      StringBuilder sb = new StringBuilder();
      if (origValue != null) {
        sb.append("[");
        boolean isFirst = true;
        for (String value : origValue.split(",")) {
          if (!isFirst) {
            sb.append(",");
          } else {
            isFirst = false;
          }

          if (flowStyle == FlowStyle.SINGLE_QUOTED) {
            sb.append("'");
          }

          sb.append(value);

          if (flowStyle == FlowStyle.SINGLE_QUOTED) {
            sb.append("'");
          }

        }
        sb.append("]");
      }
      return sb.toString();
    }
  }

  /**
   * PropertyUpdater implementation that will always return the original
   *   value for the updateForClusterCreate() method.
   *   This updater type should only be used in cases where a given
   *   property requires no updates, but may need to be considered
   *   during the Blueprint export process.
   */
  private static class OriginalValuePropertyUpdater implements PropertyUpdater {

    public String updateForClusterCreate(String propertyName,
                                         String origValue,
                                         Map<String, Map<String, String>> properties,
                                         ClusterTopology topology) {
      // always return the original value, since these properties do not require update handling
      return origValue;
    }

    @Override
    public Collection<String> getRequiredHostGroups(String origValue,
                                                    Map<String, Map<String, String>> properties,
                                                    ClusterTopology topology) {

      return Collections.emptySet();
    }
  }


  /**
   * Custom PropertyUpdater that handles the parsing and updating of the
   * "templeton.hive.properties" configuration property for WebHCat.
   * This particular configuration property uses a format of
   * comma-separated key/value pairs.  The Values in the case of the
   * hive.metastores.uri property can also contain commas, and so character
   * escaping with a backslash (\) must take place during substitution.
   *
   */
  private static class TempletonHivePropertyUpdater implements PropertyUpdater {

    private Map<String, PropertyUpdater> mapOfKeysToUpdaters =
      new HashMap<String, PropertyUpdater>();

    TempletonHivePropertyUpdater() {
      // the only known property that requires hostname substitution is hive.metastore.uris,
      // but this updater should be flexible enough for other properties in the future.
      mapOfKeysToUpdaters.put("hive.metastore.uris", new MultipleHostTopologyUpdater("HIVE_METASTORE", ',', true));
    }

    @Override
    public String updateForClusterCreate(String propertyName,
                                         String origValue,
                                         Map<String, Map<String, String>> properties,
                                         ClusterTopology topology) {

      // short-circuit out any custom property values defined by the deployer
      if (!origValue.contains("%HOSTGROUP") &&
        (!origValue.contains("localhost"))) {
        // this property must contain FQDNs specified directly by the user
        // of the Blueprint, so the processor should not attempt to update them
        return origValue;
      }

      StringBuilder updatedResult = new StringBuilder();
      // split out the key/value pairs
      String[] keyValuePairs = origValue.split(",");
      boolean firstValue = true;
      for (String keyValuePair : keyValuePairs) {
        if (!firstValue) {
          updatedResult.append(",");
        } else {
          firstValue = false;
        }

        String key = keyValuePair.split("=")[0];
        if (mapOfKeysToUpdaters.containsKey(key)) {
          String result = mapOfKeysToUpdaters.get(key).updateForClusterCreate(
              key, keyValuePair.split("=")[1], properties, topology);
          // append the internal property result, escape out any commas in the internal property,
          // this is required due to the specific syntax of templeton.hive.properties
          updatedResult.append(key);
          updatedResult.append("=");
          updatedResult.append(result.replaceAll(",", Matcher.quoteReplacement("\\,")));
        } else {
          updatedResult.append(keyValuePair);
        }
      }

      return updatedResult.toString();
    }

    @Override
    public Collection<String> getRequiredHostGroups(String origValue,
                                                    Map<String, Map<String, String>> properties,
                                                    ClusterTopology topology) {

      // short-circuit out any custom property values defined by the deployer
      if (!origValue.contains("%HOSTGROUP") &&
          (!origValue.contains("localhost"))) {
        // this property must contain FQDNs specified directly by the user
        // of the Blueprint, so the processor should not attempt to update them
        return Collections.emptySet();
      }

      Collection<String> requiredGroups = new HashSet<String>();
      // split out the key/value pairs
      String[] keyValuePairs = origValue.split(",");
      for (String keyValuePair : keyValuePairs) {
        String key = keyValuePair.split("=")[0];
        if (mapOfKeysToUpdaters.containsKey(key)) {
          requiredGroups.addAll(mapOfKeysToUpdaters.get(key).getRequiredHostGroups(
              keyValuePair.split("=")[1], properties, topology));
        }
      }
      return requiredGroups;
    }
  }

  /**
   * Register updaters for configuration properties.
   */
  static {

    allUpdaters.add(singleHostTopologyUpdaters);
    allUpdaters.add(multiHostTopologyUpdaters);
    allUpdaters.add(dbHostTopologyUpdaters);
    allUpdaters.add(mPropertyUpdaters);

    Map<String, PropertyUpdater> hdfsSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> mapredSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> coreSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> hbaseSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> yarnSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> hiveSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> oozieSiteOriginalValueMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> oozieSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> stormSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> accumuloSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> falconStartupPropertiesMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> kafkaBrokerMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> mapredEnvMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> hadoopEnvMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> hbaseEnvMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> hiveEnvMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> oozieEnvMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> oozieEnvOriginalValueMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiWebhcatSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiHbaseSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiStormSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiCoreSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiHdfsSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiHiveSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiKafkaBrokerMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiSliderClientMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiYarnSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiOozieSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiAccumuloSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> dbHiveSiteMap = new HashMap<String, PropertyUpdater>();


    singleHostTopologyUpdaters.put("hdfs-site", hdfsSiteMap);
    singleHostTopologyUpdaters.put("mapred-site", mapredSiteMap);
    singleHostTopologyUpdaters.put("core-site", coreSiteMap);
    singleHostTopologyUpdaters.put("hbase-site", hbaseSiteMap);
    singleHostTopologyUpdaters.put("yarn-site", yarnSiteMap);
    singleHostTopologyUpdaters.put("hive-site", hiveSiteMap);
    singleHostTopologyUpdaters.put("oozie-site", oozieSiteMap);
    singleHostTopologyUpdaters.put("storm-site", stormSiteMap);
    singleHostTopologyUpdaters.put("accumulo-site", accumuloSiteMap);
    singleHostTopologyUpdaters.put("falcon-startup.properties", falconStartupPropertiesMap);
    singleHostTopologyUpdaters.put("hive-env", hiveEnvMap);
    singleHostTopologyUpdaters.put("oozie-env", oozieEnvMap);
    singleHostTopologyUpdaters.put("kafka-broker", kafkaBrokerMap);

    mPropertyUpdaters.put("hadoop-env", hadoopEnvMap);
    mPropertyUpdaters.put("hbase-env", hbaseEnvMap);
    mPropertyUpdaters.put("mapred-env", mapredEnvMap);

    multiHostTopologyUpdaters.put("webhcat-site", multiWebhcatSiteMap);
    multiHostTopologyUpdaters.put("hbase-site", multiHbaseSiteMap);
    multiHostTopologyUpdaters.put("storm-site", multiStormSiteMap);
    multiHostTopologyUpdaters.put("core-site", multiCoreSiteMap);
    multiHostTopologyUpdaters.put("hdfs-site", multiHdfsSiteMap);
    multiHostTopologyUpdaters.put("hive-site", multiHiveSiteMap);
    multiHostTopologyUpdaters.put("kafka-broker", multiKafkaBrokerMap);
    multiHostTopologyUpdaters.put("slider-client", multiSliderClientMap);
    multiHostTopologyUpdaters.put("yarn-site", multiYarnSiteMap);
    multiHostTopologyUpdaters.put("oozie-site", multiOozieSiteMap);
    multiHostTopologyUpdaters.put("accumulo-site", multiAccumuloSiteMap);

    dbHostTopologyUpdaters.put("hive-site", dbHiveSiteMap);

    removePropertyUpdaters.put("oozie-env", oozieEnvOriginalValueMap);
    removePropertyUpdaters.put("oozie-site", oozieSiteOriginalValueMap);

    //todo: Need to change updaters back to being static
    //todo: will need to pass ClusterTopology in as necessary


    // NAMENODE
    hdfsSiteMap.put("dfs.http.address", new SingleHostTopologyUpdater("NAMENODE"));
    hdfsSiteMap.put("dfs.https.address", new SingleHostTopologyUpdater("NAMENODE"));
    coreSiteMap.put("fs.default.name", new SingleHostTopologyUpdater("NAMENODE"));
    hdfsSiteMap.put("dfs.namenode.http-address", new SingleHostTopologyUpdater("NAMENODE"));
    hdfsSiteMap.put("dfs.namenode.https-address", new SingleHostTopologyUpdater("NAMENODE"));
    hdfsSiteMap.put("dfs.namenode.rpc-address", new SingleHostTopologyUpdater("NAMENODE"));
    coreSiteMap.put("fs.defaultFS", new SingleHostTopologyUpdater("NAMENODE"));
    hbaseSiteMap.put("hbase.rootdir", new SingleHostTopologyUpdater("NAMENODE"));
    accumuloSiteMap.put("instance.volumes", new SingleHostTopologyUpdater("NAMENODE"));
    // HDFS shared.edits JournalNode Quorum URL uses semi-colons as separators
    multiHdfsSiteMap.put("dfs.namenode.shared.edits.dir", new MultipleHostTopologyUpdater("JOURNALNODE", ';', false));

    // SECONDARY_NAMENODE
    hdfsSiteMap.put("dfs.secondary.http.address", new SingleHostTopologyUpdater("SECONDARY_NAMENODE"));
    hdfsSiteMap.put("dfs.namenode.secondary.http-address", new SingleHostTopologyUpdater("SECONDARY_NAMENODE"));

    // JOBTRACKER
    mapredSiteMap.put("mapred.job.tracker", new SingleHostTopologyUpdater("JOBTRACKER"));
    mapredSiteMap.put("mapred.job.tracker.http.address", new SingleHostTopologyUpdater("JOBTRACKER"));
    mapredSiteMap.put("mapreduce.history.server.http.address", new SingleHostTopologyUpdater("JOBTRACKER"));


    // HISTORY_SERVER
    yarnSiteMap.put("yarn.log.server.url", new SingleHostTopologyUpdater("HISTORYSERVER"));
    mapredSiteMap.put("mapreduce.jobhistory.webapp.address", new SingleHostTopologyUpdater("HISTORYSERVER"));
    mapredSiteMap.put("mapreduce.jobhistory.address", new SingleHostTopologyUpdater("HISTORYSERVER"));

    // RESOURCEMANAGER
    yarnSiteMap.put("yarn.resourcemanager.hostname", new SingleHostTopologyUpdater("RESOURCEMANAGER"));
    yarnSiteMap.put("yarn.resourcemanager.resource-tracker.address", new SingleHostTopologyUpdater("RESOURCEMANAGER"));
    yarnSiteMap.put("yarn.resourcemanager.webapp.address", new SingleHostTopologyUpdater("RESOURCEMANAGER"));
    yarnSiteMap.put("yarn.resourcemanager.scheduler.address", new SingleHostTopologyUpdater("RESOURCEMANAGER"));
    yarnSiteMap.put("yarn.resourcemanager.address", new SingleHostTopologyUpdater("RESOURCEMANAGER"));
    yarnSiteMap.put("yarn.resourcemanager.admin.address", new SingleHostTopologyUpdater("RESOURCEMANAGER"));
    yarnSiteMap.put("yarn.resourcemanager.webapp.https.address", new SingleHostTopologyUpdater("RESOURCEMANAGER"));

    // APP_TIMELINE_SERVER
    yarnSiteMap.put("yarn.timeline-service.address", new SingleHostTopologyUpdater("APP_TIMELINE_SERVER"));
    yarnSiteMap.put("yarn.timeline-service.webapp.address", new SingleHostTopologyUpdater("APP_TIMELINE_SERVER"));
    yarnSiteMap.put("yarn.timeline-service.webapp.https.address", new SingleHostTopologyUpdater("APP_TIMELINE_SERVER"));


    // HIVE_SERVER
    multiHiveSiteMap.put("hive.metastore.uris", new MultipleHostTopologyUpdater("HIVE_METASTORE", ',', true));
    dbHiveSiteMap.put("javax.jdo.option.ConnectionURL",
        new DBTopologyUpdater("MYSQL_SERVER", "hive-env", "hive_database"));
    multiCoreSiteMap.put("hadoop.proxyuser.hive.hosts", new MultipleHostTopologyUpdater("HIVE_SERVER"));
    multiCoreSiteMap.put("hadoop.proxyuser.HTTP.hosts", new MultipleHostTopologyUpdater("WEBHCAT_SERVER"));
    multiCoreSiteMap.put("hadoop.proxyuser.hcat.hosts", new MultipleHostTopologyUpdater("WEBHCAT_SERVER"));
    multiWebhcatSiteMap.put("templeton.hive.properties", new TempletonHivePropertyUpdater());
    multiWebhcatSiteMap.put("templeton.kerberos.principal", new MultipleHostTopologyUpdater("WEBHCAT_SERVER"));
    hiveEnvMap.put("hive_hostname", new SingleHostTopologyUpdater("HIVE_SERVER"));
    multiHiveSiteMap.put("hive.zookeeper.quorum", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));
    multiHiveSiteMap.put("hive.cluster.delegation.token.store.zookeeper.connectString", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));

    // OOZIE_SERVER
    oozieSiteMap.put("oozie.base.url", new SingleHostTopologyUpdater("OOZIE_SERVER"));
    oozieSiteMap.put("oozie.authentication.kerberos.principal", new SingleHostTopologyUpdater("OOZIE_SERVER"));
    oozieSiteMap.put("oozie.service.HadoopAccessorService.kerberos.principal", new SingleHostTopologyUpdater("OOZIE_SERVER"));
    oozieEnvMap.put("oozie_hostname", new SingleHostTopologyUpdater("OOZIE_SERVER"));
    multiCoreSiteMap.put("hadoop.proxyuser.oozie.hosts", new MultipleHostTopologyUpdater("OOZIE_SERVER"));

    // register updaters for Oozie properties that may point to an external DB
    oozieEnvOriginalValueMap.put("oozie_existing_mysql_host", new OriginalValuePropertyUpdater());
    oozieSiteOriginalValueMap.put("oozie.service.JPAService.jdbc.url", new OriginalValuePropertyUpdater());

    // ZOOKEEPER_SERVER
    multiHbaseSiteMap.put("hbase.zookeeper.quorum", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));
    multiWebhcatSiteMap.put("templeton.zookeeper.hosts", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));
    multiCoreSiteMap.put("ha.zookeeper.quorum", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));
    multiYarnSiteMap.put("hadoop.registry.zk.quorum", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));
    multiYarnSiteMap.put("yarn.resourcemanager.zk-address", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));
    multiSliderClientMap.put("slider.zookeeper.quorum", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));
    multiKafkaBrokerMap.put("zookeeper.connect", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));
    multiAccumuloSiteMap.put("instance.zookeeper.host", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));

    // STORM
    stormSiteMap.put("nimbus.host", new SingleHostTopologyUpdater("NIMBUS"));
    stormSiteMap.put("worker.childopts", new OptionalSingleHostTopologyUpdater("GANGLIA_SERVER"));
    stormSiteMap.put("supervisor.childopts", new OptionalSingleHostTopologyUpdater("GANGLIA_SERVER"));
    stormSiteMap.put("nimbus.childopts", new OptionalSingleHostTopologyUpdater("GANGLIA_SERVER"));
    multiStormSiteMap.put("storm.zookeeper.servers",
        new YamlMultiValuePropertyDecorator(new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER")));
    multiStormSiteMap.put("nimbus.seeds",
        new YamlMultiValuePropertyDecorator(new MultipleHostTopologyUpdater("NIMBUS"), YamlMultiValuePropertyDecorator.FlowStyle.PLAIN));


    // FALCON
    falconStartupPropertiesMap.put("*.broker.url", new SingleHostTopologyUpdater("FALCON_SERVER"));
    falconStartupPropertiesMap.put("*.falcon.service.authentication.kerberos.principal", new SingleHostTopologyUpdater("FALCON_SERVER"));
    falconStartupPropertiesMap.put("*.falcon.http.authentication.kerberos.principal", new SingleHostTopologyUpdater("FALCON_SERVER"));

    // KAFKA
    kafkaBrokerMap.put("kafka.ganglia.metrics.host", new OptionalSingleHostTopologyUpdater("GANGLIA_SERVER"));

    // KNOX
    multiCoreSiteMap.put("hadoop.proxyuser.knox.hosts", new MultipleHostTopologyUpdater("KNOX_GATEWAY"));
    multiWebhcatSiteMap.put("webhcat.proxyuser.knox.hosts", new MultipleHostTopologyUpdater("KNOX_GATEWAY"));
    multiOozieSiteMap.put("hadoop.proxyuser.knox.hosts", new MultipleHostTopologyUpdater("KNOX_GATEWAY"));
    multiOozieSiteMap.put("oozie.service.ProxyUserService.proxyuser.knox.hosts", new MultipleHostTopologyUpdater("KNOX_GATEWAY"));


    // Required due to AMBARI-4933.  These no longer seem to be required as the default values in the stack
    // are now correct but are left here in case an existing blueprint still contains an old value.
    hadoopEnvMap.put("namenode_heapsize", new MPropertyUpdater());
    hadoopEnvMap.put("namenode_opt_newsize", new MPropertyUpdater());
    hadoopEnvMap.put("namenode_opt_maxnewsize", new MPropertyUpdater());
    hadoopEnvMap.put("namenode_opt_permsize", new MPropertyUpdater());
    hadoopEnvMap.put("namenode_opt_maxpermsize", new MPropertyUpdater());
    hadoopEnvMap.put("dtnode_heapsize", new MPropertyUpdater());
    mapredEnvMap.put("jtnode_opt_newsize", new MPropertyUpdater());
    mapredEnvMap.put("jtnode_opt_maxnewsize", new MPropertyUpdater());
    mapredEnvMap.put("jtnode_heapsize", new MPropertyUpdater());
    hbaseEnvMap.put("hbase_master_heapsize", new MPropertyUpdater());
    hbaseEnvMap.put("hbase_regionserver_heapsize", new MPropertyUpdater());
  }

  /**
   * Explicitly set any properties that are required but not currently provided in the stack definition.
   */
  void setMissingConfigurations(Map<String, Map<String, String>> mapClusterConfigurations) {
    // AMBARI-5206
    final Map<String , String> userProps = new HashMap<String , String>();

    Collection<String> services = clusterTopology.getBlueprint().getServices();
    // only add user properties to the map for
    // services actually included in the blueprint definition
    if (services.contains("OOZIE")) {
      userProps.put("oozie_user", "oozie-env");
    }

    if (services.contains("HIVE")) {
      userProps.put("hive_user", "hive-env");
      userProps.put("hcat_user", "hive-env");
    }

    if (services.contains("HBASE")) {
      userProps.put("hbase_user", "hbase-env");
    }

    if (services.contains("FALCON")) {
      userProps.put("falcon_user", "falcon-env");
    }


    String proxyUserHosts  = "hadoop.proxyuser.%s.hosts";
    String proxyUserGroups = "hadoop.proxyuser.%s.groups";

    for (String property : userProps.keySet()) {
      String configType = userProps.get(property);
      Map<String, String> configs = mapClusterConfigurations.get(configType);
      if (configs != null) {
        String user = configs.get(property);
        if (user != null && !user.isEmpty()) {
          ensureProperty(mapClusterConfigurations, "core-site", String.format(proxyUserHosts, user), "*");
          ensureProperty(mapClusterConfigurations, "core-site", String.format(proxyUserGroups, user), "users");
        }
      } else {
        LOG.debug("setMissingConfigurations: no user configuration found for type = " + configType +
                  ".  This may be caused by an error in the blueprint configuration.");
      }

    }
  }

  /**
   * Ensure that the specified property exists.
   * If not, set a default value.
   *
   * @param type          config type
   * @param property      property name
   * @param defaultValue  default value
   */
  private void ensureProperty(Map<String, Map<String, String>> mapClusterConfigurations, String type, String property, String defaultValue) {
    Map<String, String> properties = mapClusterConfigurations.get(type);
    if (properties == null) {
      properties = new HashMap<String, String>();
      mapClusterConfigurations.put(type, properties);
    }

    if (! properties.containsKey(property)) {
      properties.put(property, defaultValue);
    }
  }


  /**
   * Defines an interface for querying a filter to determine
   * if a given property should be included in an external
   * collection of properties.
   */
  private static interface PropertyFilter {

    /**
     * Query to determine if a given property should be included in a collection of
     * properties.
     *
     * @param propertyName property name
     * @param propertyValue property value
     * @return true if the property should be included
     *         false if the property should not be included
     */
    boolean isPropertyIncluded(String propertyName, String propertyValue);
  }

  /**
   * A Filter that excludes properties if the property name matches
   * a pattern of "*PASSWORD" (case-insensitive).
   *
   */
  private static class PasswordPropertyFilter implements PropertyFilter {

    private static final Pattern PASSWORD_NAME_REGEX = Pattern.compile("\\S+PASSWORD", Pattern.CASE_INSENSITIVE);

    /**
     * Query to determine if a given property should be included in a collection of
     * properties.
     *
     * This implementation uses a regular expression to determine if
     * a given property name ends with "PASSWORD", using a case-insensitive match.
     * This will be used to filter out Ranger passwords that are not considered "required"
     * passwords by the stack metadata. This could potentially also
     * be useful in filtering out properties that are added to
     * stacks, but not directly marked as the PASSWORD type, even if they
     * are indeed passwords.
     *
     *
     * @param propertyName property name
     * @param propertyValue property value
     *
     * @return true if the property should be included
     *         false if the property should not be included
     */
    @Override
    public boolean isPropertyIncluded(String propertyName, String propertyValue) {
      return !PASSWORD_NAME_REGEX.matcher(propertyName).matches();
    }
  }

}

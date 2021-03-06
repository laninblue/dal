package com.ctrip.platform.dal.dao.datasource.cluster;

import com.ctrip.framework.dal.cluster.client.config.ClusterConfigXMLConstants;
import com.ctrip.platform.dal.dao.configure.DataSourceConfigure;
import com.ctrip.platform.dal.dao.datasource.ClosableDataSource;
import com.ctrip.platform.dal.dao.datasource.DataSourceCreator;
import com.ctrip.platform.dal.dao.datasource.SingleDataSource;
import com.ctrip.platform.dal.dao.datasource.SingleDataSourceWrapper;
import com.ctrip.platform.dal.dao.helper.CustomThreadFactory;
import com.ctrip.platform.dal.dao.helper.DalElementFactory;
import com.ctrip.platform.dal.dao.helper.EnvUtils;
import com.ctrip.platform.dal.dao.log.ILogger;
import com.ctrip.platform.dal.exceptions.DalRuntimeException;
import com.ctrip.platform.dal.exceptions.InvalidConnectionException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author c7ch23en
 */
public class MultiHostDataSource extends DataSourceDelegate implements DataSource, ClosableDataSource, SingleDataSourceWrapper {

    private static final ILogger LOGGER = DalElementFactory.DEFAULT.getILogger();
    private static final EnvUtils ENV_UTILS = DalElementFactory.DEFAULT.getEnvUtils();
    private static final long DETECTION_INTERVAL_MS = 1000;

    private final ShardMeta shardMeta;
    private final Map<HostSpec, DataSourceConfigure> dataSourceConfigs;
    private final Map<HostSpec, SingleDataSource> wrappedDataSources = new HashMap<>();
    private final ConnectionFactory connFactory;
    private final RouteStrategy routeStrategy;
    private final MultiHostClusterProperties clusterProperties;
    private final ConnectionValidator connValidator;

    private final AtomicBoolean isDetecting = new AtomicBoolean(false);
    private final AtomicLong lastDetectedTime = new AtomicLong(0);

    public MultiHostDataSource(ShardMeta shardMeta, Map<HostSpec, DataSourceConfigure> dataSourceConfigs,
                               MultiHostClusterProperties clusterProperties) {
        this.shardMeta = shardMeta;
        this.dataSourceConfigs = dataSourceConfigs;
        this.clusterProperties = clusterProperties;
        this.connFactory = prepareConnectionFactory();
        this.routeStrategy = prepareRouteStrategy();
        this.connValidator = this.routeStrategy.getConnectionValidator();
        prepareDataSources();
    }

    protected ConnectionFactory prepareConnectionFactory() {
        return new ConnectionFactory() {
            @Override
            public Connection getPooledConnectionForHost(HostSpec host) throws SQLException, InvalidConnectionException {
                return wrappedDataSources.get(host).getDataSource().getConnection();
            }

            @Override
            public Connection createConnectionForHost(HostSpec host) throws SQLException, InvalidConnectionException {
                return getPooledConnectionForHost(host);
            }
        };
    }

    protected RouteStrategy prepareRouteStrategy() {
        String strategyName = clusterProperties.routeStrategyName();
        RouteStrategy strategy;
        if (ClusterConfigXMLConstants.ORDERED_ACCESS_STRATEGY.equalsIgnoreCase(strategyName))
            strategy = new OrderedAccessStrategy();
        else {
            try {
                Class clazz = Class.forName(strategyName);
                strategy = (RouteStrategy) clazz.newInstance();
            } catch (Throwable t) {
                String msg = "Errored constructing route strategy: " + strategyName;
                LOGGER.error(msg, t);
                throw new DalRuntimeException(msg, t);
            }
        }
        strategy.initialize(shardMeta, connFactory, clusterProperties.routeStrategyProperties());
        return strategy;
    }

    private void prepareDataSources() {
        dataSourceConfigs.forEach((host, config) -> {
            config.setHost(host);
            config.setValidator(connValidator);
            wrappedDataSources.put(host, prepareDataSource(config));
        });
    }

    protected SingleDataSource prepareDataSource(DataSourceConfigure dataSourceConfig) {
        return DataSourceCreator.getInstance().getOrCreateDataSourceWithoutPool(dataSourceConfig.getName(),
                dataSourceConfig, null);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return routeStrategy.pickConnection(buildRequestContext());
    }

    protected RequestContext buildRequestContext() {
        return new DefaultRequestContext(ENV_UTILS.getZone());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public void close() {
        wrappedDataSources.values().forEach(dataSource -> {
            if (dataSource != null)
                DataSourceCreator.getInstance().returnDataSource(dataSource);
        });
        if (routeStrategy != null) {
            routeStrategy.destroy();
        }
    }

    @Override
    public DataSource getDelegated() {
        return wrappedDataSources.values().iterator().next().getDataSource();
    }

    @Override
    public SingleDataSource getSingleDataSource() {
        return wrappedDataSources.values().iterator().next();
    }

    @Override
    public void forceRefreshDataSource(String name, DataSourceConfigure configure) {}

    public void handleException(SQLException e, boolean isUpdateOperation, Connection connection) {
        if (e != null && System.currentTimeMillis() - lastDetectedTime.get() > DETECTION_INTERVAL_MS &&
                isDetecting.compareAndSet(false, true))
//            executor.submit(() -> {
                try {
                    LOGGER.warn("Execution error in MultiHostDataSource", e);
                    HostConnection conn;
                    if (connection.isWrapperFor(HostConnection.class))
                        conn = connection.unwrap(HostConnection.class);
                    else
                        conn = new DefaultHostConnection(connection, null);
                    connValidator.validate(conn);
                } catch (Throwable t) {
                    // ignore
                } finally {
                    lastDetectedTime.set(System.currentTimeMillis());
                    isDetecting.set(false);
                }
//            });
    }

}

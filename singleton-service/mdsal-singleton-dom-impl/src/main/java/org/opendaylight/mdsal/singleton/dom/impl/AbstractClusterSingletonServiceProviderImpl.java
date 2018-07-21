/*
 * Copyright (c) 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.mdsal.singleton.dom.impl;

import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Verify;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.opendaylight.mdsal.eos.common.api.CandidateAlreadyRegisteredException;
import org.opendaylight.mdsal.eos.common.api.GenericEntity;
import org.opendaylight.mdsal.eos.common.api.GenericEntityOwnershipChange;
import org.opendaylight.mdsal.eos.common.api.GenericEntityOwnershipListener;
import org.opendaylight.mdsal.eos.common.api.GenericEntityOwnershipListenerRegistration;
import org.opendaylight.mdsal.eos.common.api.GenericEntityOwnershipService;
import org.opendaylight.mdsal.singleton.common.api.ClusterSingletonService;
import org.opendaylight.mdsal.singleton.common.api.ClusterSingletonServiceProvider;
import org.opendaylight.mdsal.singleton.common.api.ClusterSingletonServiceRegistration;
import org.opendaylight.yangtools.concepts.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract class {@link AbstractClusterSingletonServiceProviderImpl} represents implementations of
 * {@link ClusterSingletonServiceProvider} and it implements {@link GenericEntityOwnershipListener}
 * for providing OwnershipChange for all registered {@link ClusterSingletonServiceGroup} entity
 * candidate.
 *
 * @param <P> the instance identifier path type
 * @param <E> the GenericEntity type
 * @param <C> the GenericEntityOwnershipChange type
 * @param <G> the GenericEntityOwnershipListener type
 * @param <S> the GenericEntityOwnershipService type
 * @param <R> the GenericEntityOwnershipListenerRegistration type
 */
public abstract class AbstractClusterSingletonServiceProviderImpl<P extends Path<P>, E extends GenericEntity<P>,
        C extends GenericEntityOwnershipChange<P, E>,
        G extends GenericEntityOwnershipListener<P, C>,
        S extends GenericEntityOwnershipService<P, E, G>,
        R extends GenericEntityOwnershipListenerRegistration<P, G>>
        implements ClusterSingletonServiceProvider, GenericEntityOwnershipListener<P, C> {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractClusterSingletonServiceProviderImpl.class);

    @VisibleForTesting
    static final String SERVICE_ENTITY_TYPE = "org.opendaylight.mdsal.ServiceEntityType";
    @VisibleForTesting
    static final String CLOSE_SERVICE_ENTITY_TYPE = "org.opendaylight.mdsal.AsyncServiceCloseEntityType";

    private final S entityOwnershipService;
    private final Map<String, ClusterSingletonServiceGroup<P, E, C>> serviceGroupMap = new ConcurrentHashMap<>();

    /* EOS Entity Listeners Registration */
    private R serviceEntityListenerReg;
    private R asyncCloseEntityListenerReg;

    /**
     * Class constructor.
     *
     * @param entityOwnershipService relevant EOS
     */
    protected AbstractClusterSingletonServiceProviderImpl(@Nonnull final S entityOwnershipService) {
        this.entityOwnershipService = Preconditions.checkNotNull(entityOwnershipService);
    }

    /**
     * This method must be called once on startup to initialize this provider.
     */
    // 在dom-singleton.xml blueprint中调用
    public final void initializeProvider() {
        LOG.debug("Initialization method for ClusterSingletonService Provider {}", this);
        // 调用子类DOMClusterSingletonServiceProviderImpl的方法registerListener, 向eos注册监听
        this.serviceEntityListenerReg = registerListener(SERVICE_ENTITY_TYPE, entityOwnershipService);
        this.asyncCloseEntityListenerReg = registerListener(CLOSE_SERVICE_ENTITY_TYPE, entityOwnershipService);
    }

    /*
        1.支持单service的singleton，每个service的getIdentifier不同即可. (一个service一个group而已)
        2.支持service Group singleton，只需要各个service的各个getIdentifier一样即可.
            本质上为一个id创建选举, 第二个service注册时id一样，就会找到group并直接跳过 id选举, 直接将第二个service加入到已存在group, 然后根据group对象的localServicesState决定是否是owner
     */
    @Override
    public final synchronized ClusterSingletonServiceRegistration registerClusterSingletonService(
            @CheckForNull final ClusterSingletonService service) {
        LOG.debug("Call registrationService {} method for ClusterSingletonService Provider {}", service, this);

        final String serviceIdentifier = service.getIdentifier().getValue();
        Preconditions.checkArgument(!Strings.isNullOrEmpty(serviceIdentifier),
                "ClusterSingletonService identifier may not be null nor empty");

        final ClusterSingletonServiceGroup<P, E, C> serviceGroup;
        // 判断此service id是否存在
        ClusterSingletonServiceGroup<P, E, C> existing = serviceGroupMap.get(serviceIdentifier);
        if (existing == null) {
            // 本地不存在service, 为此创建service创建ClusterSingletonServiceGroupImpl对象
            serviceGroup = createGroup(serviceIdentifier, new ArrayList<>(1));
            serviceGroupMap.put(serviceIdentifier, serviceGroup);

            try {
                /*
                    1.注册SERVICE_ENITY_TYPE = "org.opendaylight.mdsal.ServiceEntityType"类型的此serviceGroupId选举（eos.registerCandidate()）
                    2.如果SERVICE_ENITY_TYPE注册了为owner，再注册CLOSE_SERVICE_ENTITY_TYPE = "org.opendaylight.mdsal.AsyncServiceCloseEntityType";类型的选举（eos.registerCandidate()）
                    3.如果CLOSE_SERVICE_ENTITY_TYPE 也是owner（两次确认?），那么节点就正在是此group的ownership.
                        后续再添加service到此group就会直接运行其方法instantiateServiceInstance
                 */
                initializeOrRemoveGroup(serviceGroup);
            } catch (CandidateAlreadyRegisteredException e) {
                throw new IllegalArgumentException("Service group already registered", e);
            }
        } else {
            serviceGroup = existing;
        }

        /*
            如果serviceGroup在当前节点是ownership，service执行运行instantiateServiceInstance方法
                serviceGroup有可能已经选举好了（上一个注册的service就会选举）
         */
        serviceGroup.registerService(service);
        return new AbstractClusterSingletonServiceRegistration(service) {
            @Override
            protected void removeRegistration() {
                // We need to bounce the unregistration through a ordered lock in order not to deal with asynchronous
                // shutdown of the group and user registering it again.
                AbstractClusterSingletonServiceProviderImpl.this.removeRegistration(serviceIdentifier, service);
            }
        };
    }

    private ClusterSingletonServiceGroup<P, E, C> createGroup(final String serviceIdentifier,
            final List<ClusterSingletonService> services) {
        /*
            会为每个注册的service创建ClusterSingletonServiceGroupImpl, 封装了service的identifier
         */
        return new ClusterSingletonServiceGroupImpl<>(serviceIdentifier, entityOwnershipService,
                createEntity(SERVICE_ENTITY_TYPE, serviceIdentifier),
                createEntity(CLOSE_SERVICE_ENTITY_TYPE, serviceIdentifier), services);
    }

    private void initializeOrRemoveGroup(final ClusterSingletonServiceGroup<P, E, C> group)
            throws CandidateAlreadyRegisteredException {
        try {
            /*
                效果:
                    1.注册SERVICE_ENITY_TYPE = "org.opendaylight.mdsal.ServiceEntityType"类型的此serviceGroupId选举
                    2.如果SERVICE_ENITY_TYPE注册了为owner，再注册CLOSE_SERVICE_ENTITY_TYPE = "org.opendaylight.mdsal.AsyncServiceCloseEntityType";类型的选举
                    3.如果CLOSE_SERVICE_ENTITY_TYPE 也是owner（两次确认?），那么节点就正在是此group的ownership.
                        后续再添加service到此group就会直接运行其方法instantiateServiceInstance
             */
            group.initialize();
        } catch (CandidateAlreadyRegisteredException e) {
            serviceGroupMap.remove(group.getIdentifier(), group);
            throw e;
        }
    }

    void removeRegistration(final String serviceIdentifier, final ClusterSingletonService service) {

        final PlaceholderGroup<P, E, C> placeHolder;
        final ListenableFuture<?> future;
        synchronized (this) {
            final ClusterSingletonServiceGroup<P, E, C> lookup = verifyNotNull(serviceGroupMap.get(serviceIdentifier));
            if (!lookup.unregisterService(service)) {
                return;
            }

            // Close the group and replace it with a placeholder
            LOG.debug("Closing service group {}", serviceIdentifier);
            future = lookup.closeClusterSingletonGroup();
            placeHolder = new PlaceholderGroup<>(lookup, future);

            final String identifier = service.getIdentifier().getValue();
            verify(serviceGroupMap.replace(identifier, lookup, placeHolder));
            LOG.debug("Replaced group {} with {}", serviceIdentifier, placeHolder);
        }

        future.addListener(() -> finishShutdown(placeHolder), MoreExecutors.directExecutor());
    }

    synchronized void finishShutdown(final PlaceholderGroup<P, E, C> placeHolder) {
        final String identifier = placeHolder.getIdentifier();
        LOG.debug("Service group {} closed", identifier);

        final List<ClusterSingletonService> services = placeHolder.getServices();
        if (services.isEmpty()) {
            // No services, we are all done
            if (serviceGroupMap.remove(identifier, placeHolder)) {
                LOG.debug("Service group {} removed", placeHolder);
            } else {
                LOG.debug("Service group {} superseded by {}", placeHolder, serviceGroupMap.get(identifier));
            }
            return;
        }

        // Placeholder is being retired, we are reusing its services as the seed for the group.
        final ClusterSingletonServiceGroup<P, E, C> group = createGroup(identifier, services);
        Verify.verify(serviceGroupMap.replace(identifier, placeHolder, group));
        placeHolder.setSuccessor(group);
        LOG.debug("Service group upgraded from {} to {}", placeHolder, group);

        try {
            initializeOrRemoveGroup(group);
        } catch (CandidateAlreadyRegisteredException e) {
            LOG.error("Failed to register delayed group {}, it will remain inoperational", identifier, e);
        }
    }

    @Override
    public final void close() {
        LOG.debug("Close method for ClusterSingletonService Provider {}", this);

        if (serviceEntityListenerReg != null) {
            serviceEntityListenerReg.close();
            serviceEntityListenerReg = null;
        }

        final List<ListenableFuture<?>> listGroupCloseListFuture = new ArrayList<>();

        for (final ClusterSingletonServiceGroup<P, E, C> serviceGroup : serviceGroupMap.values()) {
            listGroupCloseListFuture.add(serviceGroup.closeClusterSingletonGroup());
        }

        final ListenableFuture<List<Object>> finalCloseFuture = Futures.allAsList(listGroupCloseListFuture);
        Futures.addCallback(finalCloseFuture, new FutureCallback<List<?>>() {

            @Override
            public void onSuccess(final List<?> result) {
                cleanup();
            }

            @Override
            public void onFailure(final Throwable throwable) {
                LOG.warn("Unexpected problem by closing ClusterSingletonServiceProvider {}",
                    AbstractClusterSingletonServiceProviderImpl.this, throwable);
                cleanup();
            }
        }, MoreExecutors.directExecutor());
    }

    @Override
    public final void ownershipChanged(final C ownershipChange) {
        LOG.debug("Ownership change for ClusterSingletonService Provider {}", ownershipChange);
        final String serviceIdentifier = getServiceIdentifierFromEntity(ownershipChange.getEntity());
        final ClusterSingletonServiceGroup<P, E, C> serviceHolder = serviceGroupMap.get(serviceIdentifier);
        if (serviceHolder != null) {
            /*
                当service的ownership状态变化, 调用ClusterSingletonServiceGroupImpl的ownershipChanged方法
             */
            serviceHolder.ownershipChanged(ownershipChange);
        } else {
            LOG.debug("ClusterSingletonServiceGroup was not found for serviceIdentifier {}", serviceIdentifier);
        }
    }

    /**
     * Method implementation registers a defined {@link GenericEntityOwnershipListenerRegistration} type
     * EntityOwnershipListenerRegistration.
     *
     * @param entityType the type of the entity
     * @param entityOwnershipServiceInst - EOS type
     * @return instance of EntityOwnershipListenerRegistration
     */
    protected abstract R registerListener(String entityType, S entityOwnershipServiceInst);

    /**
     * Creates an extended {@link GenericEntity} instance.
     *
     * @param entityType the type of the entity
     * @param entityIdentifier the identifier of the entity
     * @return instance of Entity extended GenericEntity type
     */
    protected abstract E createEntity(String entityType, String entityIdentifier);

    /**
     * Method is responsible for parsing ServiceGroupIdentifier from E entity.
     *
     * @param entity instance of GenericEntity type
     * @return ServiceGroupIdentifier parsed from entity key value.
     */
    protected abstract String getServiceIdentifierFromEntity(E entity);

    /**
     * Method is called async. from close method in end of Provider lifecycle.
     */
    final void cleanup() {
        LOG.debug("Final cleaning ClusterSingletonServiceProvider {}", this);
        if (asyncCloseEntityListenerReg != null) {
            asyncCloseEntityListenerReg.close();
            asyncCloseEntityListenerReg = null;
        }
        serviceGroupMap.clear();
    }
}

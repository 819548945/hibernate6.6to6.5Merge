package xx.core.models;
import static org.hibernate.engine.internal.ManagedTypeHelper.asManagedEntity;
import static org.hibernate.engine.internal.ManagedTypeHelper.asPersistentAttributeInterceptable;
import static org.hibernate.engine.internal.ManagedTypeHelper.asSelfDirtinessTracker;
import static org.hibernate.engine.internal.ManagedTypeHelper.isHibernateProxy;
import static org.hibernate.engine.internal.ManagedTypeHelper.isPersistentAttributeInterceptable;
import static org.hibernate.engine.internal.ManagedTypeHelper.isSelfDirtinessTracker;

import org.hibernate.AssertionFailure;
import org.hibernate.HibernateException;
import org.hibernate.StaleObjectStateException;
import org.hibernate.WrongClassException;
import org.hibernate.bytecode.enhance.spi.interceptor.EnhancementAsProxyLazinessInterceptor;
import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.EntityKey;
import org.hibernate.engine.spi.ManagedEntity;
import org.hibernate.engine.spi.PersistenceContext;
import org.hibernate.engine.spi.PersistentAttributeInterceptor;
import org.hibernate.engine.spi.SelfDirtinessTracker;
import org.hibernate.event.internal.DefaultMergeEventListener;
import org.hibernate.event.spi.EventSource;
import org.hibernate.event.spi.MergeContext;
import org.hibernate.event.spi.MergeEvent;
import org.hibernate.internal.CoreLogging;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.loader.ast.spi.CascadingFetchProfile;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.stat.spi.StatisticsImplementor;

public class OldMergeEventListener extends DefaultMergeEventListener{
	private static final CoreMessageLogger LOG = CoreLogging.messageLogger( OldMergeEventListener.class );
	protected void entityIsDetached(MergeEvent event, Object copiedId, Object originalId, MergeContext copyCache) {
		LOG.trace( "Merging detached instance" );

		final Object entity = event.getEntity();
		final EventSource source = event.getSession();
		final EntityPersister persister = source.getEntityPersister( event.getEntityName(), entity );
		final String entityName = persister.getEntityName();
		if ( originalId == null ) {
			originalId = persister.getIdentifier( entity, source );
		}
		final Object clonedIdentifier;
		if ( copiedId == null ) {
			clonedIdentifier = persister.getIdentifierType().deepCopy( originalId, event.getFactory() );
		}
		else {
			clonedIdentifier = copiedId;
		}
		final Object id = getDetachedEntityId( event, originalId, persister );
		// we must clone embedded composite identifiers, or we will get back the same instance that we pass in
		// apply the special MERGE fetch profile and perform the resolution (Session#get)
		final Object result = source.getLoadQueryInfluencers().fromInternalFetchProfile(
				CascadingFetchProfile.MERGE,
				() -> source.get( entityName, clonedIdentifier )
		);

		if ( result == null ) {
			LOG.trace( "Detached instance not found in database" );
			// we got here because we assumed that an instance
			// with an assigned id and no version was detached,
			// when it was really transient (or deleted)
			/*final Boolean knownTransient = persister.isTransient( entity, source );
			//去除这段判断逻辑
			if ( knownTransient == Boolean.FALSE ) {
				// we know for sure it's detached (generated id
				// or a version property), and so the instance
				// must have been deleted by another transaction
				throw new StaleObjectStateException( entityName, id );
			}
			else {*/
				// we know for sure it's transient, or we just
				// don't have information (assigned id and no
				// version property) so keep assuming transient
				entityIsTransient( event, clonedIdentifier, copyCache );
			/*}*/
		}
		else {
			// before cascade!
			copyCache.put( entity, result, true );
			final Object target = targetEntity( event, entity, persister, id, result );
			// cascade first, so that all unsaved objects get their
			// copy created before we actually copy
			cascadeOnMerge( source, persister, entity, copyCache );
			copyValues( persister, entity, target, source, copyCache );
			//copyValues works by reflection, so explicitly mark the entity instance dirty
			markInterceptorDirty( entity, target );
			event.setResult( result );
		}
	}
	private static Object getDetachedEntityId(MergeEvent event, Object originalId, EntityPersister persister) {
		final EventSource source = event.getSession();
		final Object id = event.getRequestedId();
		if ( id == null ) {
			return originalId;
		}
		else {
			// check that entity id = requestedId
			final Object entityId = originalId;
			if ( !persister.getIdentifierType().isEqual( id, entityId, source.getFactory() ) ) {
				throw new HibernateException( "merge requested with id not matching id of passed entity" );
			}
			return id;
		}
	}
	private static Object targetEntity(MergeEvent event, Object entity, EntityPersister persister, Object id, Object result) {
		final EventSource source = event.getSession();
		final String entityName = persister.getEntityName();
		final Object target = unproxyManagedForDetachedMerging( entity, result, persister, source );
		if ( target == entity) {
			throw new AssertionFailure( "entity was not detached" );
		}
		else if ( !source.getEntityName( target ).equals( entityName ) ) {
			throw new WrongClassException(
					"class of the given object did not match class of persistent copy",
					event.getRequestedId(),
					entityName
			);
		}
		else if ( isVersionChanged( entity, source, persister, target ) ) {
			final StatisticsImplementor statistics = source.getFactory().getStatistics();
			if ( statistics.isStatisticsEnabled() ) {
				statistics.optimisticFailure( entityName );
			}
			throw new StaleObjectStateException( entityName, id );
		}
		else {
			return target;
		}
	}
	private static void markInterceptorDirty(final Object entity, final Object target) {
		// for enhanced entities, copy over the dirty attributes
		if ( isSelfDirtinessTracker( entity ) && isSelfDirtinessTracker( target ) ) {
			// clear, because setting the embedded attributes dirties them
			final ManagedEntity managedEntity = asManagedEntity( target );
			final boolean useTracker = asManagedEntity( entity ).$$_hibernate_useTracker();
			final SelfDirtinessTracker selfDirtinessTrackerTarget = asSelfDirtinessTracker( target );
			if ( !selfDirtinessTrackerTarget.$$_hibernate_hasDirtyAttributes() &&  !useTracker ) {
				managedEntity.$$_hibernate_setUseTracker( false );
			}
			else {
				managedEntity.$$_hibernate_setUseTracker( true );
				selfDirtinessTrackerTarget.$$_hibernate_clearDirtyAttributes();
				for ( String fieldName : asSelfDirtinessTracker( entity ).$$_hibernate_getDirtyAttributes() ) {
					selfDirtinessTrackerTarget.$$_hibernate_trackChange( fieldName );
				}
			}
		}
	}
	private static Object unproxyManagedForDetachedMerging(
			Object incoming,
			Object managed,
			EntityPersister persister,
			EventSource source) {
		if ( isHibernateProxy( managed ) ) {
			return source.getPersistenceContextInternal().unproxy( managed );
		}

		if ( isPersistentAttributeInterceptable( incoming )
				&& persister.getBytecodeEnhancementMetadata().isEnhancedForLazyLoading() ) {

			final PersistentAttributeInterceptor incomingInterceptor =
					asPersistentAttributeInterceptable( incoming ).$$_hibernate_getInterceptor();
			final PersistentAttributeInterceptor managedInterceptor =
					asPersistentAttributeInterceptable( managed ).$$_hibernate_getInterceptor();

			// todo - do we need to specially handle the case where both `incoming` and `managed` are initialized, but
			//		with different attributes initialized?
			// 		- for now, assume we do not...

			// if the managed entity is not a proxy, we can just return it
			if ( ! ( managedInterceptor instanceof EnhancementAsProxyLazinessInterceptor ) ) {
				return managed;
			}

			// if the incoming entity is still a proxy there is no need to force initialization of the managed one
			if ( incomingInterceptor instanceof EnhancementAsProxyLazinessInterceptor ) {
				return managed;
			}

			// otherwise, force initialization
			persister.initializeEnhancedEntityUsedAsProxy( managed, null, source );
		}

		return managed;
	}
	private static boolean isVersionChanged(Object entity, EventSource source, EntityPersister persister, Object target) {
		if ( persister.isVersioned() ) {
			// for merging of versioned entities, we consider the version having
			// been changed only when:
			// 1) the two version values are different;
			//      *AND*
			// 2) The target actually represents database state!
			//
			// This second condition is a special case which allows
			// an entity to be merged during the same transaction
			// (though during a separate operation) in which it was
			// originally persisted/saved
			boolean changed = !persister.getVersionType().isSame(
					persister.getVersion( target ),
					persister.getVersion( entity )
			);
			// TODO : perhaps we should additionally require that the incoming entity
			// version be equivalent to the defined unsaved-value?
			return changed && existsInDatabase( target, source, persister );
		}
		else {
			return false;
		}
	}
	private static boolean existsInDatabase(Object entity, EventSource source, EntityPersister persister) {
		final PersistenceContext persistenceContext = source.getPersistenceContextInternal();
		EntityEntry entry = persistenceContext.getEntry( entity );
		if ( entry == null ) {
			Object id = persister.getIdentifier( entity, source );
			if ( id != null ) {
				final EntityKey key = source.generateEntityKey( id, persister );
				final Object managedEntity = persistenceContext.getEntity( key );
				entry = persistenceContext.getEntry( managedEntity );
			}
		}

		return entry != null && entry.isExistsInDatabase();
	}
}

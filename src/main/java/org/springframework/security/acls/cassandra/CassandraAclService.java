/* Copyright 2013 Rigas Grigoropoulos
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.acls.cassandra;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.security.acls.cassandra.model.AclEntry;
import org.springframework.security.acls.cassandra.model.AclObjectIdentity;
import org.springframework.security.acls.cassandra.repository.CassandraAclRepository;
import org.springframework.security.acls.domain.AccessControlEntryImpl;
import org.springframework.security.acls.domain.AclAuthorizationStrategy;
import org.springframework.security.acls.domain.AclImpl;
import org.springframework.security.acls.domain.DefaultPermissionFactory;
import org.springframework.security.acls.domain.ObjectIdentityImpl;
import org.springframework.security.acls.domain.PermissionFactory;
import org.springframework.security.acls.model.AccessControlEntry;
import org.springframework.security.acls.model.Acl;
import org.springframework.security.acls.model.AclCache;
import org.springframework.security.acls.model.AclService;
import org.springframework.security.acls.model.NotFoundException;
import org.springframework.security.acls.model.ObjectIdentity;
import org.springframework.security.acls.model.PermissionGrantingStrategy;
import org.springframework.security.acls.model.Sid;
import org.springframework.security.util.FieldUtils;
import org.springframework.util.Assert;

/**
 * Implementation of {@link AclService} using the {@link CassandraAclRepository} to access ACLs stored in Cassandra.
 * 
 * @author Rigas Grigoropoulos
 *
 */
public class CassandraAclService implements AclService {

	private static final Log LOG = LogFactory.getLog(CassandraAclService.class);

	protected final CassandraAclRepository aclRepository;
	protected final AclCache aclCache;
	private PermissionFactory permissionFactory = new DefaultPermissionFactory();
	private AclAuthorizationStrategy aclAuthorizationStrategy;
	private PermissionGrantingStrategy grantingStrategy;

	private final Field fieldAces = FieldUtils.getField(AclImpl.class, "aces");

	/**
	 * @param aclRepository
	 * @param aclCache
	 * @param grantingStrategy
	 * @param aclAuthorizationStrategy
	 */
	public CassandraAclService(CassandraAclRepository aclRepository, AclCache aclCache, PermissionGrantingStrategy grantingStrategy,
			AclAuthorizationStrategy aclAuthorizationStrategy) {
		this.aclRepository = aclRepository;
		this.aclCache = aclCache;
		this.grantingStrategy = grantingStrategy;
		this.aclAuthorizationStrategy = aclAuthorizationStrategy;
		this.fieldAces.setAccessible(true);
	}

	
	/* (non-Javadoc)
	 * @see org.springframework.security.acls.model.AclService#findChildren(org.springframework.security.acls.model.ObjectIdentity)
	 */
	public List<ObjectIdentity> findChildren(ObjectIdentity parentIdentity) {
		Assert.notNull(parentIdentity, "Object to lookup required");
		
		if (LOG.isDebugEnabled()) {
			LOG.debug("BEGIN findChildren: parentIdentity: " + parentIdentity );
		}
		
		List<ObjectIdentity> result = null;		
		List<AclObjectIdentity> children = aclRepository.findAclObjectIdentityChildren(new AclObjectIdentity(parentIdentity));
		if (children != null && !children.isEmpty()) {
			result = new ArrayList<ObjectIdentity>();
			for (AclObjectIdentity entry : children) {
				result.add(entry.toObjectIdentity());
			}
		}
		
		if (LOG.isDebugEnabled()) {
			LOG.debug("END findChildren: children: " + result);
		}
		return result;
	}

	/* (non-Javadoc)
	 * @see org.springframework.security.acls.model.AclService#readAclById(org.springframework.security.acls.model.ObjectIdentity)
	 */
	public Acl readAclById(ObjectIdentity object) throws NotFoundException {
		return readAclById(object, null);
	}

	
	/* (non-Javadoc)
	 * @see org.springframework.security.acls.model.AclService#readAclById(org.springframework.security.acls.model.ObjectIdentity, java.util.List)
	 */
	public Acl readAclById(ObjectIdentity object, List<Sid> sids) throws NotFoundException {
		Map<ObjectIdentity, Acl> map = readAclsById(Arrays.asList(object), sids);
		Assert.isTrue(map.containsKey(object), "There should have been an Acl entry for ObjectIdentity " + object);
		return (Acl) map.get(object);
	}

	/* (non-Javadoc)
	 * @see org.springframework.security.acls.model.AclService#readAclsById(java.util.List)
	 */
	public Map<ObjectIdentity, Acl> readAclsById(List<ObjectIdentity> objects) throws NotFoundException {
		return readAclsById(objects, null);
	}

	/* (non-Javadoc)
	 * @see org.springframework.security.acls.model.AclService#readAclsById(java.util.List, java.util.List)
	 */
	public Map<ObjectIdentity, Acl> readAclsById(List<ObjectIdentity> objects, List<Sid> sids) throws NotFoundException {
		Assert.notEmpty(objects, "Objects to lookup required");
		
		if (LOG.isDebugEnabled()) {
			LOG.debug("BEGIN readAclById: objectIdentities: " + objects + ", sids: " + sids);
		}

		// contains FULLY loaded Acl objects
		Map<ObjectIdentity, Acl> result = new HashMap<ObjectIdentity, Acl>();
		List<ObjectIdentity> objectsToLookup = new ArrayList<ObjectIdentity>(objects);

		// Check for Acls in the cache
		if (aclCache != null) {
			for (ObjectIdentity oi : objects) {
				boolean aclLoaded = false;

				Acl acl = aclCache.getFromCache(oi);
				if (acl != null && acl.isSidLoaded(sids)) {
					// Ensure any cached element supports all the requested SIDs
					result.put(oi, acl);
					aclLoaded = true;
				}
				if (aclLoaded) {
					objectsToLookup.remove(oi);
				}
			}
		}

		if (!objectsToLookup.isEmpty()) {
			Map<ObjectIdentity, Acl> loadedAcls = doLookup(objectsToLookup);
			result.putAll(loadedAcls);

			// Put loaded Acls in the cache
			if (aclCache != null) {
				for (Acl loadedAcl : loadedAcls.values()) {
					aclCache.putInCache((AclImpl) loadedAcl);
				}
			}
		}

		for (ObjectIdentity oid : objects) {
			if (!result.containsKey(oid)) {
				throw new NotFoundException("Unable to find ACL information for object identity '" + oid + "'");
			}
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("END readAclById: acls: " + result.values());
		}
		return result;
	}

	/**
	 * @param objects
	 * @return
	 */
	private Map<ObjectIdentity, Acl> doLookup(List<ObjectIdentity> objects) {
		Map<ObjectIdentity, Acl> result = new HashMap<ObjectIdentity, Acl>();
		
		if (objects != null && !objects.isEmpty()) {
			List<AclObjectIdentity> objectIds = new ArrayList<AclObjectIdentity>();

			for (ObjectIdentity objId : objects) {
				objectIds.add(new AclObjectIdentity(objId));
			}

			Map<AclObjectIdentity, Set<AclEntry>> aeList = aclRepository.findAcls(objectIds);		
			Map<ObjectIdentity, Acl> parentAcls = lookupParents(aeList.keySet());

			for (Entry<AclObjectIdentity, Set<AclEntry>> entry : aeList.entrySet()) {
				Acl parentAcl = parentAcls.get(entry.getKey().getParentObjectIdentity());
				AclImpl loadedAcl = convert(entry.getKey(), entry.getValue(), parentAcl);
				result.put(loadedAcl.getObjectIdentity(), loadedAcl);
			}
		}		
		return result;
	}

	/**
	 * @param acls
	 * @return
	 */
	private Map<ObjectIdentity, Acl> lookupParents(Set<AclObjectIdentity> acls) {
		List<ObjectIdentity> objectsToLookup = new ArrayList<ObjectIdentity>();
		for (AclObjectIdentity aoi : acls) {
			if (aoi.getParentObjectId() != null && !aoi.getParentObjectId().isEmpty()
					&& aoi.getParentObjectClass() != null && !aoi.getParentObjectClass().isEmpty()) {
				objectsToLookup.add(new ObjectIdentityImpl(aoi.getParentObjectClass(), aoi.getParentObjectId()));
			}
		}
		return doLookup(objectsToLookup);
	}

	/**
	 * @param aclObjectIdentity
	 * @param aclEntries
	 * @param parentAcl
	 * @return
	 */
	private AclImpl convert(AclObjectIdentity aclObjectIdentity, Set<AclEntry> aclEntries, Acl parentAcl) {
		AclImpl acl = new AclImpl(aclObjectIdentity.toObjectIdentity(), aclObjectIdentity.getId(),
				aclAuthorizationStrategy, grantingStrategy, parentAcl, null, aclObjectIdentity.isEntriesInheriting(), aclObjectIdentity.getOwnerSid());

		List<AccessControlEntry> aces = new ArrayList<AccessControlEntry>(aclEntries.size());
		for (AclEntry entry : aclEntries) {
			AccessControlEntry ace = new AccessControlEntryImpl(entry.getId(), acl, entry.getSidObject(), permissionFactory.buildFromMask(entry.getMask()),
					entry.isGranting(), entry.isAuditSuccess(), entry.isAuditFailure());
			aces.add(entry.getOrder(), ace);
		}

		try {
			fieldAces.set(acl, aces);
		} catch (Exception e) {
			LOG.error("Could not set AccessControlEntries in the ACL", e);
		}
		return acl;
	}
}

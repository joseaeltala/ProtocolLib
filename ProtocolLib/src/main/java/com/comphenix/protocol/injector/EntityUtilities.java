/*
 *  ProtocolLib - Bukkit server library that allows access to the Minecraft protocol.
 *  Copyright (C) 2012 Kristian S. Stangeland
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the 
 *  GNU General Public License as published by the Free Software Foundation; either version 2 of 
 *  the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
 *  See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with this program; 
 *  if not, write to the Free Software Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 
 *  02111-1307 USA
 */

package com.comphenix.protocol.injector;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.server.EntityPlayer;
import net.minecraft.server.EntityTrackerEntry;

import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import com.comphenix.protocol.reflect.FieldAccessException;
import com.comphenix.protocol.reflect.FieldUtils;
import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.google.common.collect.Lists;

/**
 * Used to perform certain operations on entities.
 * 
 * @author Kristian
 */
class EntityUtilities {

	private static Field entityTrackerField;
	private static Field trackedEntitiesField;
	private static Field trackedPlayersField;
	
	private static Method hashGetMethod;
	private static Method scanPlayersMethod;
	
	/*
	 * While this function may look pretty bad, it's essentially just a reflection-warped 
	 * version of the following:
	 * 
	 *  	@SuppressWarnings("unchecked")
	 *	 	public static void updateEntity2(Entity entity, List<Player> observers) {
	 *
	 *			World world = entity.getWorld();
	 *			WorldServer worldServer = ((CraftWorld) world).getHandle();
	 *
	 *			EntityTracker tracker = worldServer.tracker;
	 *			EntityTrackerEntry entry = (EntityTrackerEntry) tracker.trackedEntities.get(entity.getEntityId());
	 *
	 *			List<EntityPlayer> nmsPlayers = getNmsPlayers(observers);
	 *
	 *			entry.trackedPlayers.removeAll(nmsPlayers);
	 *			entry.scanPlayers(nmsPlayers);
	 *		}
	 *
	 *		private static List<EntityPlayer> getNmsPlayers(List<Player> players) {
	 *			List<EntityPlayer> nsmPlayers = new ArrayList<EntityPlayer>();
	 *	
	 *			for (Player bukkitPlayer : players) {
	 *				CraftPlayer craftPlayer = (CraftPlayer) bukkitPlayer;
	 *				nsmPlayers.add(craftPlayer.getHandle());
	 *			}
	 *	
	 *			return nsmPlayers;
	 *		}
	 *
	 */
	public static void updateEntity(Entity entity, List<Player> observers) throws FieldAccessException {
		try {
			//EntityTrackerEntry trackEntity = (EntityTrackerEntry) tracker.trackedEntities.get(entity.getEntityId());
			Object trackerEntry = getEntityTrackerEntry(entity.getWorld(), entity.getEntityId());

			if (trackedPlayersField == null) {
				// This one is fairly easy
				trackedPlayersField = FuzzyReflection.fromObject(trackerEntry).getFieldByType("java\\.util\\..*");
			}
			
			// Phew, finally there.
			Collection<?> trackedPlayers = (Collection<?>) FieldUtils.readField(trackedPlayersField, trackerEntry, false);
			List<Object> nmsPlayers = unwrapBukkit(observers);
			
			// trackEntity.trackedPlayers.clear();
			trackedPlayers.removeAll(nmsPlayers);
			
			// We have to rely on a NAME once again. Damn it.
			if (scanPlayersMethod == null) {
				scanPlayersMethod = trackerEntry.getClass().getMethod("scanPlayers", List.class);
			}
			
			//trackEntity.scanPlayers(server.players);
			scanPlayersMethod.invoke(trackerEntry, nmsPlayers);
			
		} catch (IllegalArgumentException e) {
			throw e;
		} catch (IllegalAccessException e) {
			throw new FieldAccessException("Security limitation prevents access to 'get' method in IntHashMap", e);
		} catch (InvocationTargetException e) {
			throw new RuntimeException("Exception occurred in Minecraft.", e);
		} catch (SecurityException e) {
			throw new FieldAccessException("Security limitation prevents access to 'scanPlayers' method in trackerEntry.", e);
		} catch (NoSuchMethodException e) {
			throw new FieldAccessException("Cannot find 'scanPlayers' method. Is ProtocolLib up to date?", e);
		}
	}

	/**
	 * Retrieve every client that is receiving information about a given entity.
	 * @param entity - the entity that is being tracked.
	 * @return Every client/player that is tracking the given entity.
	 * @throws FieldAccessException If reflection failed.
	 */
	public static List<Player> getEntityTrackers(Entity entity) {
		try {
			List<Player> result = new ArrayList<Player>();
			Object trackerEntry = getEntityTrackerEntry(entity.getWorld(), entity.getEntityId());

			if (trackedPlayersField == null) 
				trackedPlayersField = FuzzyReflection.fromObject(trackerEntry).getFieldByType("java\\.util\\..*");
			
			Collection<?> trackedPlayers = (Collection<?>) FieldUtils.readField(trackedPlayersField, trackerEntry, false);
			
			// Wrap every player - we also ensure that the underlying tracker list is immutable
			for (Object tracker : trackedPlayers) {
				if (tracker instanceof EntityPlayer) {
					EntityPlayer nmsPlayer = (EntityPlayer) tracker;
					result.add(nmsPlayer.getBukkitEntity());
				}
			}
			return result;
			
		} catch (IllegalAccessException e) {
			throw new FieldAccessException("Security limitation prevented access to the list of tracked players.", e);
		} catch (InvocationTargetException e) {
			throw new FieldAccessException("Exception occurred in Minecraft.", e);
		}
	}
	
	/**
	 * Retrieve the entity tracker entry given a ID.
	 * @param world - world server.
	 * @param entityID - entity ID.
	 * @return The entity tracker entry.
	 * @throws FieldAccessException 
	 */
	private static Object getEntityTrackerEntry(World world, int entityID) throws FieldAccessException, IllegalArgumentException, IllegalAccessException, InvocationTargetException {
		Object worldServer = ((CraftWorld) world).getHandle();

		// We have to rely on the class naming here.
		if (entityTrackerField == null)
			entityTrackerField = FuzzyReflection.fromObject(worldServer).getFieldByType(".*Tracker");
		
		// Get the tracker
		Object tracker = null;
		
		try {
			tracker = FieldUtils.readField(entityTrackerField, worldServer, false);
		} catch (IllegalAccessException e) {
			throw new FieldAccessException("Cannot access 'tracker' field due to security limitations.", e);
		}
		
		if (trackedEntitiesField == null) {
			@SuppressWarnings("rawtypes")
			Set<Class> ignoredTypes = new HashSet<Class>();
			
			// Well, this is more difficult. But we're looking for a Minecraft object that is not 
			// created by the constructor(s).
			for (Constructor<?> constructor : tracker.getClass().getConstructors()) {
				for (Class<?> type : constructor.getParameterTypes()) {
					ignoredTypes.add(type);
				}
			}
			
			// The Minecraft field that's NOT filled in by the constructor
			trackedEntitiesField = FuzzyReflection.fromObject(tracker, true).
						getFieldByType(MinecraftReflection.MINECRAFT_OBJECT, ignoredTypes);
		}
		
		// Read the entity hashmap
		Object trackedEntities = null;

		try {
			trackedEntities = FieldUtils.readField(trackedEntitiesField, tracker, true);
		} catch (IllegalAccessException e) {
			throw new FieldAccessException("Cannot access 'trackedEntities' field due to security limitations.", e);
		}
		
		// Getting the "get" method is pretty hard, but first - try to just get it by name
		if (hashGetMethod == null) {
			
			Class<?> type = trackedEntities.getClass();
			
			try {
				hashGetMethod = type.getMethod("get", int.class);
			} catch (NoSuchMethodException e) {
			
				Class<?>[] params = { int.class };
				
				// Then it's probably the lowest named method that takes an int-parameter
				for (Method method : type.getMethods()) {
					if (Arrays.equals(params, method.getParameterTypes())) {
						if (hashGetMethod == null ||
							method.getName().compareTo(hashGetMethod.getName()) < 0) {
							hashGetMethod = method;
						}
					}
				}
			}
		}
		
		// Wrap exceptions
		try {
			return hashGetMethod.invoke(trackedEntities, entityID);
		} catch (IllegalArgumentException e) {
			throw e;
		} catch (IllegalAccessException e) {
			throw new FieldAccessException("Security limitation prevents access to 'get' method in IntHashMap", e);
		} catch (InvocationTargetException e) {
			throw new RuntimeException("Exception occurred in Minecraft.", e);
		}
	}
	
	/**
	 * Retrieve entity from a ID, even it it's newly created.
	 * @return The asssociated entity.
	 * @throws FieldAccessException Reflection error.
	 */
	public static Entity getEntityFromID(World world, int entityID) throws FieldAccessException {
		try {
			EntityTrackerEntry trackerEntry = (EntityTrackerEntry) getEntityTrackerEntry(world, entityID);
			
			// Handle NULL cases
			if (trackerEntry != null && trackerEntry.tracker != null) {
				return trackerEntry.tracker.getBukkitEntity();
			} else {
				return null;
			}
			
		} catch (Exception e) {
			throw new FieldAccessException("Cannot find entity from ID.", e);
		}
	}
	
	private static List<Object> unwrapBukkit(List<Player> players) {
		
		List<Object> output = Lists.newArrayList();
		BukkitUnwrapper unwrapper = new BukkitUnwrapper();
		
		// Get the NMS equivalent
		for (Player player : players) {
			Object result = unwrapper.unwrapItem(player);
			
			if (result != null)
				output.add(result);
			else
				throw new IllegalArgumentException("Cannot unwrap item " + player);
		}
		
		return output;
	}
}

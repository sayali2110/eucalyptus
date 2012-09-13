/*************************************************************************
 * Copyright 2009-2012 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 *
 * This file may incorporate work covered under the following copyright
 * and permission notice:
 *
 *   Software License Agreement (BSD License)
 *
 *   Copyright (c) 2008, Regents of the University of California
 *   All rights reserved.
 *
 *   Redistribution and use of this software in source and binary forms,
 *   with or without modification, are permitted provided that the
 *   following conditions are met:
 *
 *     Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *     Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer
 *     in the documentation and/or other materials provided with the
 *     distribution.
 *
 *   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 *   "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 *   LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 *   FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 *   COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 *   INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 *   BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *   LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 *   CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 *   LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 *   ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 *   POSSIBILITY OF SUCH DAMAGE. USERS OF THIS SOFTWARE ACKNOWLEDGE
 *   THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE LICENSED MATERIAL,
 *   COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS SOFTWARE,
 *   AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *   IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA,
 *   SANTA BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY,
 *   WHICH IN THE REGENTS' DISCRETION MAY INCLUDE, WITHOUT LIMITATION,
 *   REPLACEMENT OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO
 *   IDENTIFIED, OR WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT
 *   NEEDED TO COMPLY WITH ANY SUCH LICENSES OR RIGHTS.
 ************************************************************************/
package com.eucalyptus.reporting.art.generator;

import java.util.*;

import org.apache.log4j.Logger;

import com.eucalyptus.entities.EntityWrapper;
import com.eucalyptus.reporting.art.AbstractReportingTreeFactory;
import com.eucalyptus.reporting.art.entity.*;
import com.eucalyptus.reporting.domain.*;
import com.eucalyptus.reporting.event_store.ReportingInstanceCreateEvent;
import com.eucalyptus.reporting.event_store.ReportingInstanceUsageEvent;

public class InstanceArtGenerator
{
    private static Logger log = Logger.getLogger( AbstractReportingTreeFactory.class );

    public InstanceArtGenerator()
	{
		
	}
	
	public ReportArtEntity generateReportArt(ReportArtEntity report)
	{
		log.debug("GENERATING REPORT ART");
		EntityWrapper wrapper = EntityWrapper.get( ReportingInstanceCreateEvent.class );
		Iterator iter = wrapper.scanWithNativeQuery( "scanInstanceCreateEvents" );

		/* Create super-tree of availZones, clusters, accounts, users, and instances;
		 * and create a Map of the instance nodes at the bottom.
		 */
		Map<String,InstanceArtEntity> instanceEntities = new HashMap<String,InstanceArtEntity>();
		while (iter.hasNext()) {
			ReportingInstanceCreateEvent createEvent = (ReportingInstanceCreateEvent) iter.next();
			if (! report.getZones().containsKey(createEvent.getAvailabilityZone())) {
				report.getZones().put(createEvent.getAvailabilityZone(), new AvailabilityZoneArtEntity());
			}
			AvailabilityZoneArtEntity zone = report.getZones().get(createEvent.getAvailabilityZone());
			if (! zone.getClusters().containsKey(createEvent.getClusterName())) {
				zone.getClusters().put(createEvent.getClusterName(), new ClusterArtEntity());
			}
			ClusterArtEntity cluster = zone.getClusters().get(createEvent.getClusterName());
			
			ReportingUser reportingUser = ReportingUserDao.getInstance().getReportingUser(createEvent.getUserId());
			if (reportingUser==null) {
				log.error("No user corresponding to event:" + createEvent.getUserId());
			}
			ReportingAccount reportingAccount = ReportingAccountDao.getInstance().getReportingAccount(reportingUser.getAccountId());
			if (reportingAccount==null) {
				log.error("No account corresponding to user:" + reportingUser.getAccountId());
			}
			if (! cluster.getAccounts().containsKey(reportingAccount.getName())) {
				cluster.getAccounts().put(reportingAccount.getName(), new AccountArtEntity());
			}
			AccountArtEntity account = cluster.getAccounts().get(reportingAccount.getName());
			if (! account.getUsers().containsKey(reportingUser.getName())) {
				account.getUsers().put(reportingUser.getName(), new UserArtEntity());
			}
			UserArtEntity user = account.getUsers().get(reportingUser.getName());
			if (! user.getInstances().containsKey(createEvent.getUuid())) {
				user.getInstances().put(createEvent.getUuid(), new InstanceArtEntity(createEvent.getInstanceType(), createEvent.getInstanceId()));
			}
			InstanceArtEntity instance = user.getInstances().get(createEvent.getUuid());
			instanceEntities.put(createEvent.getUuid(), instance);
		}
		
		/* Add all usage to all instances, interpolating usage for partial periods
		 */
		Map<String,ReportingInstanceUsageEvent> lastEvents = new HashMap<String,ReportingInstanceUsageEvent>();
		iter = wrapper.scanWithNativeQuery( "scanInstanceUsageEvents" );
		while (iter.hasNext()) {
			ReportingInstanceUsageEvent usageEvent = (ReportingInstanceUsageEvent) iter.next();
			ReportingInstanceUsageEvent lastEvent = lastEvents.get(usageEvent.getUuid());
			lastEvents.put(usageEvent.getUuid(), usageEvent);
			InstanceArtEntity instance = instanceEntities.get(usageEvent.getUuid());
			if (instance==null) {
				log.error("instance usage without corresponding instance:" + usageEvent.getUuid());
				continue;
			}
			InstanceUsageArtEntity usage = instance.getUsage();
			

			/* Add usage to totals, and update average cpu */
			if (lastEvent != null) {
				long lastMs = lastEvent.getTimestampMs();
				
				/* Add usage to totals */
				usage.setDiskIoMegs(
						plus(usage.getDiskIoMegs(),
								subtract(usageEvent.getCumulativeDiskIoMegs(),
										lastEvent.getCumulativeDiskIoMegs())));
				
				usage.setNetIoWithinZoneInMegs(
						plus(usage.getNetIoWithinZoneInMegs(),
								subtract(usageEvent.getCumulativeNetIncomingMegsWithinZone(),
										lastEvent.getCumulativeNetIncomingMegsWithinZone())));
					
				usage.setNetIoBetweenZoneInMegs(
						plus(usage.getNetIoBetweenZoneInMegs(),
								subtract(usageEvent.getCumulativeNetIncomingMegsBetweenZones(),
										lastEvent.getCumulativeNetIncomingMegsBetweenZones())));
				
				usage.setNetIoPublicIpInMegs(
						plus(usage.getNetIoPublicIpInMegs(),
								subtract(usageEvent.getCumulativeNetIncomingMegsPublic(),
										lastEvent.getCumulativeNetIncomingMegsPublic())));
				
				usage.setNetIoWithinZoneOutMegs(
						plus(usage.getNetIoWithinZoneOutMegs(),
								subtract(usageEvent.getCumulativeNetOutgoingMegsWithinZone(),
										lastEvent.getCumulativeNetOutgoingMegsWithinZone())));
					
				usage.setNetIoBetweenZoneOutMegs(
						plus(usage.getNetIoBetweenZoneOutMegs(),
								subtract(usageEvent.getCumulativeNetOutgoingMegsBetweenZones(),
										lastEvent.getCumulativeNetOutgoingMegsBetweenZones())));
				
				usage.setNetIoPublicIpOutMegs(
						plus(usage.getNetIoPublicIpOutMegs(),
								subtract(usageEvent.getCumulativeNetOutgoingMegsPublic(),
										usageEvent.getCumulativeNetOutgoingMegsPublic())));
					

				/* Update cpu average */
				long durationMs = Math.min(report.getEndMs(), usageEvent.getTimestampMs())-Math.max(report.getBeginMs(), lastMs);
				if (usage.getCpuPercentAvg() == null && usageEvent.getCpuUtilizationPercent() != null) {
					usage.setCpuPercentAvg((double)usageEvent.getCpuUtilizationPercent());
					usage.addDurationMs(durationMs);
				} else if (usage.getCpuPercentAvg() != null && usageEvent.getCpuUtilizationPercent() != null) {
					double newWeightedAverage = (usage.getCpuPercentAvg() * (double)usage.getDurationMs()
							+ (double)usageEvent.getCpuUtilizationPercent() * (double)durationMs)
							/((double)usage.getDurationMs()+(double)durationMs);
					usage.setCpuPercentAvg(newWeightedAverage);
					usage.addDurationMs(durationMs);
				}
			}

			
		} //while

		
		/* Perform totals and summations
		 */
		for (String zoneName : report.getZones().keySet()) {
			AvailabilityZoneArtEntity zone = report.getZones().get(zoneName);
			UsageTotalsArtEntity zoneUsage = zone.getUsageTotals();
			for (String clusterName : zone.getClusters().keySet()) {
				ClusterArtEntity cluster = zone.getClusters().get(clusterName);
				UsageTotalsArtEntity clusterUsage = cluster.getUsageTotals();
				for (String accountName : cluster.getAccounts().keySet()) {
					AccountArtEntity account = cluster.getAccounts().get(accountName);
					UsageTotalsArtEntity accountUsage = account.getUsageTotals();
					for (String userName : account.getUsers().keySet()) {
						UserArtEntity user = account.getUsers().get(userName);
						UsageTotalsArtEntity userUsage = user.getUsageTotals();
						for (String instanceUuid : user.getInstances().keySet()) {
							InstanceArtEntity instance = user.getInstances().get(instanceUuid);
							updateUsageTotals(userUsage, instance);
							updateUsageTotals(accountUsage, instance);
							updateUsageTotals(clusterUsage, instance);
							updateUsageTotals(zoneUsage, instance);
						}
					}
				}
			}
		}

		return report;
	}

	
	private static void updateUsageTotals(UsageTotalsArtEntity totals, InstanceArtEntity instance)
	{
		InstanceUsageArtEntity totalEntity =
			totals.getInstanceTotals();
		InstanceUsageArtEntity newEntity = instance.getUsage();
		
		/* Update total average for this instance type, based upon instance average */
		if (totalEntity.getCpuPercentAvg() == null && newEntity.getCpuPercentAvg() != null) {
			totalEntity.setCpuPercentAvg(newEntity.getCpuPercentAvg());
			totalEntity.addDurationMs(newEntity.getDurationMs());
		} else if (newEntity.getCpuPercentAvg() != null && totalEntity.getCpuPercentAvg() != null) {
			double newWeightedAverage = (totalEntity.getCpuPercentAvg() * (double)totalEntity.getDurationMs()
					+ (double)newEntity.getCpuPercentAvg() * (double)newEntity.getDurationMs())
					/((double)totalEntity.getDurationMs()+(double)newEntity.getDurationMs());
			totalEntity.setCpuPercentAvg(newWeightedAverage);
			totalEntity.addDurationMs(newEntity.getDurationMs());
		}
		
		/* Add megs from this instance to totals for this instance type */
		totalEntity.setDiskIoMegs(
				plus(totalEntity.getDiskIoMegs(),
					 newEntity.getDiskIoMegs()));
		totalEntity.setNetIoBetweenZoneInMegs(
				plus(totalEntity.getNetIoBetweenZoneInMegs(),
				     newEntity.getNetIoBetweenZoneInMegs()));
		totalEntity.setNetIoWithinZoneInMegs(
				plus(totalEntity.getNetIoWithinZoneInMegs(),
					 newEntity.getNetIoWithinZoneInMegs()));
		totalEntity.setNetIoPublicIpInMegs(
				plus(totalEntity.getNetIoPublicIpInMegs(),
					 newEntity.getNetIoPublicIpInMegs()));
		totalEntity.setNetIoBetweenZoneOutMegs(
				plus(totalEntity.getNetIoBetweenZoneOutMegs(),
				     newEntity.getNetIoBetweenZoneOutMegs()));
		totalEntity.setNetIoWithinZoneOutMegs(
				plus(totalEntity.getNetIoWithinZoneOutMegs(),
					 newEntity.getNetIoWithinZoneOutMegs()));
		totalEntity.setNetIoPublicIpOutMegs(
				plus(totalEntity.getNetIoPublicIpOutMegs(),
					 newEntity.getNetIoPublicIpOutMegs()));
	}

	/**
	 * Interpolate usage based upon the fraction of the usage in some period which falls within
	 * report boundaries. 
	 */
	private static Long interpolate(long repBegin, long repEnd, long perBegin, long perEnd, Long currValue)
	{
		if (currValue==null) return null;
		
		final double periodDuration = (perEnd-perBegin);
		double factor = 0d;
		if (perEnd <= repBegin || perBegin >= repEnd) {
			//Period falls completely outside of report, on either end
			factor = 0d;
		} else if (perBegin < repBegin && perEnd <= repEnd) {
			//Period begin comes before report begin but period end lies within it
			factor = ((double)perEnd-repBegin)/periodDuration;
		} else if (perBegin >= repBegin && perEnd >= repEnd) {
			//Period begin lies within report but period end comes after it
			 factor = ((double)repEnd-perBegin)/periodDuration;
		} else if (perBegin >= repBegin && perEnd <= repEnd) {
			//Period falls entirely within report
			factor = 1d;
		} else if (repBegin >= perBegin && repEnd <= perEnd) {
			//Report falls entirely within period (<15 second report?)
			factor = ((double)(repBegin-perBegin)+(perEnd-repEnd))/periodDuration;
		} else {
			throw new IllegalStateException("impossible boundary condition");
		}

		if (factor < 0 || factor > 1) throw new IllegalStateException("factor<0 || factor>1");
		
//		log.debug(String.format("remainingFactor, report:%d-%d (%d), period:%d-%d (%d), factor:%f",
//				repBegin, repEnd, repEnd-repBegin, perBegin, perEnd, perEnd-perBegin, factor));

		return (long)(((double)currValue.longValue())*factor);
	}

	/**
	 * Addition with the peculiar semantics for null we need here
	 */
	private static Long plus(Long added, Long defaultVal)
	{
		if (added==null) {
			return defaultVal;
		} else if (defaultVal==null) {
			return added;
		} else {
			return (added.longValue() + defaultVal.longValue());
		}
		
	}
	
	/**
	 * Subtraction with the peculiar semantics we need here: previous value of null means zero
	 *    whereas current value of null returns null.
	 */
	private static Long subtract(Long currCumul, Long prevCumul)
	{
		if (currCumul==null) {
			return null;
		} else if (prevCumul==null) {
			return currCumul;
		} else {
		    return new Long(currCumul.longValue()-prevCumul.longValue());	
		}
	}

	private static Long max(Long a, Long b)
	{
		if (a==null) {
			return b;
		} else if (b==null) {
			return a;
		} else {
			return new Long(Math.max(a.longValue(), b.longValue()));
		}
	}

}

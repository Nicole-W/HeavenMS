/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as
 published by the Free Software Foundation version 3 as published by
 the Free Software Foundation. You may not use, modify or distribute
 this program under any other version of the GNU Affero General Public
 License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.server;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.Security;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import net.server.audit.ThreadTracker;
import net.server.audit.locks.MonitoredLockType;
import net.server.audit.locks.MonitoredReentrantLock;
import net.server.audit.locks.MonitoredReentrantReadWriteLock;

import net.MapleServerHandler;
import net.mina.MapleCodecFactory;
import net.server.channel.Channel;
import net.server.guild.MapleAlliance;
import net.server.guild.MapleGuild;
import net.server.guild.MapleGuildCharacter;
import net.server.worker.CharacterDiseaseWorker;
import net.server.worker.CouponWorker;
import net.server.worker.RankingWorker;
import net.server.world.World;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.buffer.SimpleBufferAllocator;
import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.service.IoAcceptor;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;

import client.MapleClient;
import client.MapleCharacter;
import client.SkillFactory;
import client.inventory.Item;
import client.inventory.ItemFactory;
import client.newyear.NewYearCardRecord;
import constants.ItemConstants;
import constants.GameConstants;
import constants.ServerConstants;
import server.CashShop.CashItemFactory;
import server.TimerManager;
import server.life.MaplePlayerNPCFactory;
import server.quest.MapleQuest;
import tools.AutoJCE;
import tools.DatabaseConnection;
import tools.FilePrinter;
import tools.Pair;

public class Server {
    private static final Set<Integer> activeFly = new HashSet<>();
    private static final Map<Integer, Integer> couponRates = new HashMap<>(30);
    private static final List<Integer> activeCoupons = new LinkedList<>();
    
    private IoAcceptor acceptor;
    private List<Map<Integer, String>> channels = new LinkedList<>();
    private List<World> worlds = new ArrayList<>();
    private final Properties subnetInfo = new Properties();
    private static Server instance = null;
    private final Map<Integer, Set<Integer>> accountChars = new HashMap<>();
    private final Map<Integer, Integer> worldChars = new HashMap<>();
    private final Map<String, Integer> transitioningChars = new HashMap<>();
    private List<Pair<Integer, String>> worldRecommendedList = new LinkedList<>();
    private final Map<Integer, MapleGuild> guilds = new HashMap<>(100);
    private final Map<MapleClient, Long> inLoginState = new HashMap<>(100);
    private final Lock srvLock = new MonitoredReentrantLock(MonitoredLockType.SERVER);
    private final Lock disLock = new MonitoredReentrantLock(MonitoredLockType.SERVER_DISEASES);
    private final ReentrantReadWriteLock lgnLock = new MonitoredReentrantReadWriteLock(MonitoredLockType.SERVER_LOGIN, true);
    private final ReadLock lgnRLock = lgnLock.readLock();
    private final WriteLock lgnWLock = lgnLock.writeLock();
    private final PlayerBuffStorage buffStorage = new PlayerBuffStorage();
    private final Map<Integer, MapleAlliance> alliances = new HashMap<>(100);
    private final Map<Integer, NewYearCardRecord> newyears = new HashMap<>();
    private final List<MapleClient> processDiseaseAnnouncePlayers = new LinkedList<>();
    private final List<MapleClient> registeredDiseaseAnnouncePlayers = new LinkedList<>();
    
    private final AtomicLong currentTime = new AtomicLong(0);
    private long serverCurrentTime = 0;
    
    private boolean availableDeveloperRoom = false;
    private boolean online = false;
    public static long uptime = System.currentTimeMillis();
    
    public static Server getInstance() {
        if (instance == null) {
            instance = new Server();
        }
        return instance;
    }

    public long getCurrentTime() {  // returns a slightly delayed time value, under frequency of UPDATE_INTERVAL
        return serverCurrentTime;
    }
    
    public void updateCurrentTime() {
        serverCurrentTime = currentTime.addAndGet(ServerConstants.UPDATE_INTERVAL);
    }
    
    public long forceUpdateCurrentTime() {
        long timeNow = System.currentTimeMillis();
        serverCurrentTime = timeNow;
        currentTime.set(timeNow);
        
        return timeNow;
    }
    
    public boolean isOnline() {
        return online;
    }

    public List<Pair<Integer, String>> worldRecommendedList() {
        return worldRecommendedList;
    }
    
    public void setNewYearCard(NewYearCardRecord nyc) {
        newyears.put(nyc.getId(), nyc);
    }
    
    public NewYearCardRecord getNewYearCard(int cardid) {
        return newyears.get(cardid);
    }
    
    public NewYearCardRecord removeNewYearCard(int cardid) {
        return newyears.remove(cardid);
    }
    
    public void setAvailableDeveloperRoom() {
        availableDeveloperRoom = true;
    }
    
    public boolean canEnterDeveloperRoom() {
        return availableDeveloperRoom;
    }

    private void loadPlayerNpcMapStepFromDb() {
        try {
            Connection con = DatabaseConnection.getConnection();
            PreparedStatement ps = con.prepareStatement("SELECT * FROM playernpcs_field");
                        
            ResultSet rs = ps.executeQuery();
            while(rs.next()) {
                int world = rs.getInt("world"), map = rs.getInt("map"), step = rs.getInt("step"), podium = rs.getInt("podium");
                worlds.get(world).setPlayerNpcMapData(map, step, podium);
            }
            
            rs.close();
            ps.close();
            con.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    
    /*
    public void removeChannel(int worldid, int channel) {   //lol don't!
        channels.remove(channel);

        World world = worlds.get(worldid);
        if (world != null) {
            world.removeChannel(channel);
        }
    }
    */

    public Channel getChannel(int world, int channel) {
        return worlds.get(world).getChannel(channel);
    }

    public List<Channel> getChannelsFromWorld(int world) {
        return worlds.get(world).getChannels();
    }

    public List<Channel> getAllChannels() {
        List<Channel> channelz = new ArrayList<>();
        for (World world : worlds) {
            for (Channel ch : world.getChannels()) {
                channelz.add(ch);
            }
        }
        return channelz;
    }

    public String getIP(int world, int channel) {
        return channels.get(world).get(channel);
    }
    
    private long getTimeLeftForNextHour() {
        Calendar nextHour = Calendar.getInstance();
        nextHour.add(Calendar.HOUR, 1);
        nextHour.set(Calendar.MINUTE, 0);
        nextHour.set(Calendar.SECOND, 0);
        
        return Math.max(0, nextHour.getTimeInMillis() - System.currentTimeMillis());
    }
    
    public Map<Integer, Integer> getCouponRates() {
        return couponRates;
    }
    
    private void loadCouponRates(Connection c) throws SQLException {
        PreparedStatement ps = c.prepareStatement("SELECT couponid, rate FROM nxcoupons");
        ResultSet rs = ps.executeQuery();
        
        while(rs.next()) {
            int cid = rs.getInt("couponid");
            int rate = rs.getInt("rate");
            
            couponRates.put(cid, rate);
        }
        
        rs.close();
        ps.close();
    }
    
    public List<Integer> getActiveCoupons() {
        synchronized(activeCoupons) {
            return activeCoupons;
        }
    }
    
    public void commitActiveCoupons() {
        for(World world: getWorlds()) {
            for(MapleCharacter chr: world.getPlayerStorage().getAllCharacters()) {
                if(!chr.isLoggedin()) continue;

                chr.updateCouponRates();
            }
        }
    }
    
    public void toggleCoupon(Integer couponId) {
        if(ItemConstants.isRateCoupon(couponId)) {
            synchronized(activeCoupons) {
                if(activeCoupons.contains(couponId)) {
                    activeCoupons.remove(couponId);
                }
                else {
                    activeCoupons.add(couponId);
                }

                commitActiveCoupons();
            }
        }
    }
    
    public void updateActiveCoupons() throws SQLException {
        synchronized(activeCoupons) {
            activeCoupons.clear();
            Calendar c = Calendar.getInstance();

            int weekDay = c.get(Calendar.DAY_OF_WEEK);
            int hourDay = c.get(Calendar.HOUR_OF_DAY);

            Connection con = null;
            try {
                con = DatabaseConnection.getConnection();

                int weekdayMask = (1 << weekDay);
                PreparedStatement ps = con.prepareStatement("SELECT couponid FROM nxcoupons WHERE (activeday & ?) = ? AND starthour <= ? AND endhour > ?");
                ps.setInt(1, weekdayMask);
                ps.setInt(2, weekdayMask);
                ps.setInt(3, hourDay);
                ps.setInt(4, hourDay);

                ResultSet rs = ps.executeQuery();
                while(rs.next()) {
                    activeCoupons.add(rs.getInt("couponid"));
                }

                rs.close();
                ps.close();

                con.close();
            } catch (SQLException ex) {
                ex.printStackTrace();

                try {
                    if(con != null && !con.isClosed()) {
                        con.close();
                    }
                } catch (SQLException ex2) {
                    ex2.printStackTrace();
                }
            }
        }
    }
    
    public void runAnnouncePlayerDiseasesSchedule() {
        List<MapleClient> processDiseaseAnnounceClients;
        disLock.lock();
        try {
            processDiseaseAnnounceClients = new LinkedList<>(processDiseaseAnnouncePlayers);
            processDiseaseAnnouncePlayers.clear();
        } finally {
            disLock.unlock();
        }
        
        while(!processDiseaseAnnounceClients.isEmpty()) {
            MapleClient c = processDiseaseAnnounceClients.remove(0);
            MapleCharacter player = c.getPlayer();
            if(player != null && player.isLoggedinWorld()) {
                for(MapleCharacter chr : player.getMap().getCharacters()) {
                    chr.announceDiseases(c);
                }
            }
        }
        
        disLock.lock();
        try {
            // this is to force the system to wait for at least one complete tick before releasing disease info for the registered clients
            while(!registeredDiseaseAnnouncePlayers.isEmpty()) {
                MapleClient c = registeredDiseaseAnnouncePlayers.remove(0);
                processDiseaseAnnouncePlayers.add(c);
            }
        } finally {
            disLock.unlock();
        }
    }
    
    public void registerAnnouncePlayerDiseases(MapleClient c) {
        disLock.lock();
        try {
            registeredDiseaseAnnouncePlayers.add(c);
        } finally {
            disLock.unlock();
        }
    }
    
    public void init() {
        Properties p = new Properties();
        try {
            p.load(new FileInputStream("world.ini"));
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("[SEVERE] Could not find/open 'world.ini'.");
            System.exit(0);
        }

        System.out.println("HeavenMS v" + ServerConstants.VERSION + " starting up.\r\n");


        if(ServerConstants.SHUTDOWNHOOK)
            Runtime.getRuntime().addShutdownHook(new Thread(shutdown(false)));
        
        Connection c = null;
        try {
            c = DatabaseConnection.getConnection();
            PreparedStatement ps = c.prepareStatement("UPDATE accounts SET loggedin = 0");
            ps.executeUpdate();
            ps.close();
            ps = c.prepareStatement("UPDATE characters SET HasMerchant = 0");
            ps.executeUpdate();
            ps.close();
            
            loadCouponRates(c);
            updateActiveCoupons();
            
            c.close();
        } catch (SQLException sqle) {
            sqle.printStackTrace();
        }
        IoBuffer.setUseDirectBuffer(false);
        IoBuffer.setAllocator(new SimpleBufferAllocator());
        acceptor = new NioSocketAcceptor();
        acceptor.getFilterChain().addLast("codec", (IoFilter) new ProtocolCodecFilter(new MapleCodecFactory()));
        
        TimerManager tMan = TimerManager.getInstance();
        tMan.start();
        tMan.register(tMan.purge(), ServerConstants.PURGING_INTERVAL);//Purging ftw...
        disconnectIdlesOnLoginTask();
        
        long timeLeft = getTimeLeftForNextHour();
        tMan.register(new CharacterDiseaseWorker(), ServerConstants.UPDATE_INTERVAL, ServerConstants.UPDATE_INTERVAL);
        tMan.register(new CouponWorker(), ServerConstants.COUPON_INTERVAL, timeLeft);
        tMan.register(new RankingWorker(), ServerConstants.RANKING_INTERVAL, timeLeft);
        
        long timeToTake = System.currentTimeMillis();
        SkillFactory.loadAllSkills();
        System.out.println("Skills loaded in " + ((System.currentTimeMillis() - timeToTake) / 1000.0) + " seconds");

        timeToTake = System.currentTimeMillis();
        //MapleItemInformationProvider.getInstance().getAllItems(); //unused, rofl

        CashItemFactory.getSpecialCashItems();
        System.out.println("Items loaded in " + ((System.currentTimeMillis() - timeToTake) / 1000.0) + " seconds");
        
	timeToTake = System.currentTimeMillis();
	MapleQuest.loadAllQuest();
	System.out.println("Quest loaded in " + ((System.currentTimeMillis() - timeToTake) / 1000.0) + " seconds\r\n");
	
        NewYearCardRecord.startPendingNewYearCardRequests();
        
        if(ServerConstants.USE_THREAD_TRACKER) ThreadTracker.getInstance().registerThreadTrackerTask();
        
        try {
            Integer worldCount = Math.min(GameConstants.WORLD_NAMES.length, Integer.parseInt(p.getProperty("worlds")));
            
            for (int i = 0; i < worldCount; i++) {
                System.out.println("Starting world " + i);
                World world = new World(i,
                        Integer.parseInt(p.getProperty("flag" + i)),
                        p.getProperty("eventmessage" + i),
                        ServerConstants.EXP_RATE,
                        ServerConstants.DROP_RATE,
                        ServerConstants.MESO_RATE,
                        ServerConstants.QUEST_RATE);

                worldRecommendedList.add(new Pair<>(i, p.getProperty("whyamirecommended" + i)));
                worlds.add(world);
                channels.add(new HashMap<Integer, String>());
                long bootTime = System.currentTimeMillis();
                for (int j = 0; j < Integer.parseInt(p.getProperty("channels" + i)); j++) {
                    int channelid = j + 1;
                    Channel channel = new Channel(i, channelid, bootTime);
                    world.addChannel(channel);
                    channels.get(i).put(channelid, channel.getIP());
                }
                world.setServerMessage(p.getProperty("servermessage" + i));
                System.out.println("Finished loading world " + i + "\r\n");
            }
            
            MaplePlayerNPCFactory.loadFactoryMetadata();
            loadPlayerNpcMapStepFromDb();
        } catch (Exception e) {
            e.printStackTrace();//For those who get errors
            System.out.println("[SEVERE] Syntax error in 'world.ini'.");
            System.exit(0);
        }

        acceptor.getSessionConfig().setIdleTime(IdleStatus.BOTH_IDLE, 30);
        acceptor.setHandler(new MapleServerHandler());
        try {
            acceptor.bind(new InetSocketAddress(8484));
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        
        System.out.println("Listening on port 8484\r\n\r\n");

        System.out.println("HeavenMS is now online.\r\n");
        online = true;
    }

    public void shutdown() {
    	try {
	        TimerManager.getInstance().stop();
	        acceptor.unbind();
    	} catch (NullPointerException e) {
    		FilePrinter.printError(FilePrinter.EXCEPTION_CAUGHT, e);
    	}
        System.out.println("Server offline.");
        System.exit(0);// BOEIEND :D
    }

    public static void main(String args[]) {
        System.setProperty("wzpath", "wz");
        Security.setProperty("crypto.policy", "unlimited");
        AutoJCE.removeCryptographyRestrictions();
        Server.getInstance().init();
    }

    public Properties getSubnetInfo() {
        return subnetInfo;
    }

    public MapleAlliance getAlliance(int id) {
        synchronized (alliances) {
            if (alliances.containsKey(id)) {
                return alliances.get(id);
            }
            return null;
        }
    }

    public void addAlliance(int id, MapleAlliance alliance) {
        synchronized (alliances) {
            if (!alliances.containsKey(id)) {
                alliances.put(id, alliance);
            }
        }
    }

    public void disbandAlliance(int id) {
        synchronized (alliances) {
            MapleAlliance alliance = alliances.get(id);
            if (alliance != null) {
                for (Integer gid : alliance.getGuilds()) {
                    guilds.get(gid).setAllianceId(0);
                }
                alliances.remove(id);
            }
        }
    }

    public void allianceMessage(int id, final byte[] packet, int exception, int guildex) {
        MapleAlliance alliance = alliances.get(id);
        if (alliance != null) {
            for (Integer gid : alliance.getGuilds()) {
                if (guildex == gid) {
                    continue;
                }
                MapleGuild guild = guilds.get(gid);
                if (guild != null) {
                    guild.broadcast(packet, exception);
                }
            }
        }
    }

    public boolean addGuildtoAlliance(int aId, int guildId) {
        MapleAlliance alliance = alliances.get(aId);
        if (alliance != null) {
            alliance.addGuild(guildId);
            guilds.get(guildId).setAllianceId(aId);
            return true;
        }
        return false;
    }

    public boolean removeGuildFromAlliance(int aId, int guildId) {
        MapleAlliance alliance = alliances.get(aId);
        if (alliance != null) {
            alliance.removeGuild(guildId);
            guilds.get(guildId).setAllianceId(0);
            return true;
        }
        return false;
    }

    public boolean setAllianceRanks(int aId, String[] ranks) {
        MapleAlliance alliance = alliances.get(aId);
        if (alliance != null) {
            alliance.setRankTitle(ranks);
            return true;
        }
        return false;
    }

    public boolean setAllianceNotice(int aId, String notice) {
        MapleAlliance alliance = alliances.get(aId);
        if (alliance != null) {
            alliance.setNotice(notice);
            return true;
        }
        return false;
    }

    public boolean increaseAllianceCapacity(int aId, int inc) {
        MapleAlliance alliance = alliances.get(aId);
        if (alliance != null) {
            alliance.increaseCapacity(inc);
            return true;
        }
        return false;
    }

    public Set<Integer> getChannelServer(int world) {
        return new HashSet<>(channels.get(world).keySet());
    }

    public byte getHighestChannelId() {
        byte highest = 0;
        for (Iterator<Integer> it = channels.get(0).keySet().iterator(); it.hasNext();) {
            Integer channel = it.next();
            if (channel != null && channel.intValue() > highest) {
                highest = channel.byteValue();
            }
        }
        return highest;
    }

    public int createGuild(int leaderId, String name) {
        return MapleGuild.createGuild(leaderId, name);
    }
    
    public MapleGuild getGuildByName(String name) {
        synchronized (guilds) {
            for(MapleGuild mg: guilds.values()) {
                if(mg.getName().equalsIgnoreCase(name)) {
                    return mg;
                }
            }
            
            return null;
        }
    }
    
    public MapleGuild getGuild(int id) {
        synchronized (guilds) {
            if (guilds.get(id) != null) {
                return guilds.get(id);
            }
            
            return null;
        }
    }

    public MapleGuild getGuild(int id, int world) {
            return getGuild(id, world, null);
    }
    
    public MapleGuild getGuild(int id, int world, MapleCharacter mc) {
        synchronized (guilds) {
            if (guilds.get(id) != null) {
                return guilds.get(id);
            }
            MapleGuild g = new MapleGuild(id, world);
            if (g.getId() == -1) {
                return null;
            }
            
            if(mc != null) {
                mc.setMGC(g.getMGC(mc.getId()));
                if(g.getMGC(mc.getId()) == null) System.out.println("null for " + mc.getName() + " when loading guild " + id);
                g.getMGC(mc.getId()).setCharacter(mc);
                g.setOnline(mc.getId(), true, mc.getClient().getChannel());
            }
            
            guilds.put(id, g);
            return g;
        }
    }

    public void clearGuilds() {//remake
        synchronized (guilds) {
            guilds.clear();
        }
        //for (List<Channel> world : worlds.values()) {
        //reloadGuildCharacters();
    }

    public void setGuildMemberOnline(MapleCharacter mc, boolean bOnline, int channel) {
        MapleGuild g = getGuild(mc.getGuildId(), mc.getWorld(), mc);
        g.setOnline(mc.getId(), bOnline, channel);
    }

    public int addGuildMember(MapleGuildCharacter mgc, MapleCharacter chr) {
        MapleGuild g = guilds.get(mgc.getGuildId());
        if (g != null) {
            return g.addGuildMember(mgc, chr);
        }
        return 0;
    }

    public boolean setGuildAllianceId(int gId, int aId) {
        MapleGuild guild = guilds.get(gId);
        if (guild != null) {
            guild.setAllianceId(aId);
            return true;
        }
        return false;
    }
    
    public void resetAllianceGuildPlayersRank(int gId) {
        guilds.get(gId).resetAllianceGuildPlayersRank();
    }

    public void leaveGuild(MapleGuildCharacter mgc) {
        MapleGuild g = guilds.get(mgc.getGuildId());
        if (g != null) {
            g.leaveGuild(mgc);
        }
    }

    public void guildChat(int gid, String name, int cid, String msg) {
        MapleGuild g = guilds.get(gid);
        if (g != null) {
            g.guildChat(name, cid, msg);
        }
    }

    public void changeRank(int gid, int cid, int newRank) {
        MapleGuild g = guilds.get(gid);
        if (g != null) {
            g.changeRank(cid, newRank);
        }
    }

    public void expelMember(MapleGuildCharacter initiator, String name, int cid) {
        MapleGuild g = guilds.get(initiator.getGuildId());
        if (g != null) {
            g.expelMember(initiator, name, cid);
        }
    }

    public void setGuildNotice(int gid, String notice) {
        MapleGuild g = guilds.get(gid);
        if (g != null) {
            g.setGuildNotice(notice);
        }
    }

    public void memberLevelJobUpdate(MapleGuildCharacter mgc) {
        MapleGuild g = guilds.get(mgc.getGuildId());
        if (g != null) {
            g.memberLevelJobUpdate(mgc);
        }
    }

    public void changeRankTitle(int gid, String[] ranks) {
        MapleGuild g = guilds.get(gid);
        if (g != null) {
            g.changeRankTitle(ranks);
        }
    }

    public void setGuildEmblem(int gid, short bg, byte bgcolor, short logo, byte logocolor) {
        MapleGuild g = guilds.get(gid);
        if (g != null) {
            g.setGuildEmblem(bg, bgcolor, logo, logocolor);
        }
    }

    public void disbandGuild(int gid) {
        synchronized (guilds) {
            MapleGuild g = guilds.get(gid);
            g.disbandGuild();
            guilds.remove(gid);
        }
    }

    public boolean increaseGuildCapacity(int gid) {
        MapleGuild g = guilds.get(gid);
        if (g != null) {
            return g.increaseCapacity();
        }
        return false;
    }

    public void gainGP(int gid, int amount) {
        MapleGuild g = guilds.get(gid);
        if (g != null) {
            g.gainGP(amount);
        }
    }
	
    public void guildMessage(int gid, byte[] packet) {
        guildMessage(gid, packet, -1);
    }
	
    public void guildMessage(int gid, byte[] packet, int exception) {
        MapleGuild g = guilds.get(gid);
        if(g != null) {
            g.broadcast(packet, exception);
        }
    }

    public PlayerBuffStorage getPlayerBuffStorage() {
        return buffStorage;
    }

    public void deleteGuildCharacter(MapleCharacter mc) {
        setGuildMemberOnline(mc, false, (byte) -1);
        if (mc.getMGC().getGuildRank() > 1) {
            leaveGuild(mc.getMGC());
        } else {
            disbandGuild(mc.getMGC().getGuildId());
        }
    }
    
    public void deleteGuildCharacter(MapleGuildCharacter mgc) {
        if(mgc.getCharacter() != null) setGuildMemberOnline(mgc.getCharacter(), false, (byte) -1);
        if (mgc.getGuildRank() > 1) {
            leaveGuild(mgc);
        } else {
            disbandGuild(mgc.getGuildId());
        }
    }

    public void reloadGuildCharacters(int world) {
        World worlda = getWorld(world);
        for (MapleCharacter mc : worlda.getPlayerStorage().getAllCharacters()) {
            if (mc.getGuildId() > 0) {
                setGuildMemberOnline(mc, true, worlda.getId());
                memberLevelJobUpdate(mc.getMGC());
            }
        }
        worlda.reloadGuildSummary();
    }

    public void broadcastMessage(int world, final byte[] packet) {
        for (Channel ch : getChannelsFromWorld(world)) {
            ch.broadcastPacket(packet);
        }
    }

    public void broadcastGMMessage(int world, final byte[] packet) {
        for (Channel ch : getChannelsFromWorld(world)) {
            ch.broadcastGMPacket(packet);
        }
    }
    
    public boolean isGmOnline(int world) {
        for (Channel ch : getChannelsFromWorld(world)) {
        	for (MapleCharacter player : ch.getPlayerStorage().getAllCharacters()) {
        		if (player.isGM()){
        			return true;
        		}
        	}
        }
        return false;
    }
    
    public void changeFly(Integer accountid, boolean canFly) {
        if(canFly) {
            activeFly.add(accountid);
        } else {
            activeFly.remove(accountid);
        }
    }
    
    public boolean canFly(Integer accountid) {
        return activeFly.contains(accountid);
    }
    
    public World getWorld(int id) {
        return worlds.get(id);
    }

    public List<World> getWorlds() {
        return worlds;
    }
    
    public int getCharacterWorld(Integer chrid) {
        lgnRLock.lock();
        try {
            Integer worldid = worldChars.get(chrid);
            return worldid != null ? worldid : -1;
        } finally {
            lgnRLock.unlock();
        }
    }
    
    public boolean haveCharacterEntry(Integer accountid, Integer chrid) {
        lgnRLock.lock();
        try {
            Set<Integer> accChars = accountChars.get(accountid);
            return accChars.contains(chrid);
        } finally {
            lgnRLock.unlock();
        }
    }
    
    private Set<Integer> getAccountCharacterEntries(Integer accountid) {
        lgnRLock.lock();
        try {
            return new HashSet<>(accountChars.get(accountid));
        } finally {
            lgnRLock.unlock();
        }
    }
    
    public void updateCharacterEntry(MapleCharacter chr) {
        MapleCharacter chrView = chr.generateCharacterEntry();
        World wserv = worlds.get(chrView.getWorld());
        
        lgnWLock.lock();
        try {
            wserv.registerAccountCharacterView(chrView.getAccountID(), chrView);
        } finally {
            lgnWLock.unlock();
        }
    }
    
    public void createCharacterEntry(MapleCharacter chr) {
        Integer accountid = chr.getAccountID(), chrid = chr.getId(), world = chr.getWorld();
        
        lgnWLock.lock();
        try {
            Set<Integer> accChars = accountChars.get(accountid);
            accChars.add(chrid);
            
            worldChars.put(chrid, world);
            
            MapleCharacter chrView = chr.generateCharacterEntry();
            World wserv = worlds.get(chrView.getWorld());
            wserv.registerAccountCharacterView(chrView.getAccountID(), chrView);
        } finally {
            lgnWLock.unlock();
        }
    }
    
    public void deleteCharacterEntry(Integer accountid, Integer chrid) {
        lgnWLock.lock();
        try {
            Set<Integer> accChars = accountChars.get(accountid);
            accChars.remove(chrid);
            
            Integer world = worldChars.remove(chrid);
            if(world != null) {
                World wserv = worlds.get(world);
                wserv.unregisterAccountCharacterView(accountid, chrid);
            }
        } finally {
            lgnWLock.unlock();
        }
    }
    
    public void transferWorldCharacterEntry(MapleCharacter chr, Integer toWorld) { // used before setting the new worldid on the character object
        lgnWLock.lock();
        try {
            Integer chrid = chr.getId(), accountid = chr.getAccountID(), world = worldChars.get(chr.getId());
            if(world != null) {
                World wserv = worlds.get(world);
                wserv.unregisterAccountCharacterView(accountid, chrid);
            }
            
            worldChars.put(chrid, toWorld);
            
            MapleCharacter chrView = chr.generateCharacterEntry();
            World wserv = worlds.get(toWorld);
            wserv.registerAccountCharacterView(chrView.getAccountID(), chrView);
        } finally {
            lgnWLock.unlock();
        }
    }
    
    /*
    public void deleteAccountEntry(Integer accountid) { is this even a thing?
        lgnWLock.lock();
        try {
            accountChars.remove(accountid);
        } finally {
            lgnWLock.unlock();
        }
    }
    */
    
    public Pair<Pair<Integer, List<MapleCharacter>>, List<Pair<Integer, List<MapleCharacter>>>> loadAccountCharlist(Integer accountId) {
        List<World> wlist = worlds;
        List<Pair<Integer, List<MapleCharacter>>> accChars = new ArrayList<>(wlist.size() + 1);
        int chrTotal = 0;
        List<MapleCharacter> lastwchars = null;
        
        lgnRLock.lock();
        try {
            for(World w : wlist) {
                List<MapleCharacter> wchars = w.getAccountCharactersView(accountId);
                if(wchars == null) {
                    if(!accountChars.containsKey(accountId)) {
                        accountChars.put(accountId, new HashSet<Integer>());    // not advisable at all to write on the map on a read-protected environment
                    }                                                           // yet it's known there's no problem since no other point in the source does
                } else if(!wchars.isEmpty()) {                                  // this action.
                    lastwchars = wchars;

                    accChars.add(new Pair<>(w.getId(), wchars));
                    chrTotal += wchars.size();
                }
            }
        } finally {
            lgnRLock.unlock();
        }
        
        return new Pair<>(new Pair<>(chrTotal, lastwchars), accChars);
    }
    
    private static List<List<MapleCharacter>> loadAccountCharactersViewFromDb(MapleClient c, int wlen) {
        List<List<MapleCharacter>> wchars = new ArrayList<>(wlen);
        for(int i = 0; i < wlen; i++) wchars.add(i, new LinkedList<MapleCharacter>());
        
        List<MapleCharacter> chars = new LinkedList<>();
        int curWorld = 0;
        try {
            List<Pair<Item, Integer>> accEquips = ItemFactory.loadEquippedItems(c.getAccID(), true, true);
            Map<Integer, List<Item>> accPlayerEquips = new HashMap<>();
            
            for(Pair<Item, Integer> ae : accEquips) {
                List<Item> playerEquips = accPlayerEquips.get(ae.getRight());
                if(playerEquips == null) {
                    playerEquips = new LinkedList<>();
                    accPlayerEquips.put(ae.getRight(), playerEquips);
                }
                
                playerEquips.add(ae.getLeft());
            }
            
            Connection con = DatabaseConnection.getConnection();
            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM characters WHERE accountid = ? ORDER BY world, id")) {
                ps.setInt(1, c.getAccID());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int cworld = rs.getByte("world");
                        if(cworld >= wlen) break;

                        if(cworld > curWorld) {
                            wchars.add(curWorld, chars);

                            curWorld = cworld;
                            chars = new LinkedList<>();
                        }
                        
                        Integer cid = rs.getInt("id");
                        chars.add(MapleCharacter.loadCharacterEntryFromDB(rs, accPlayerEquips.get(cid)));
                    }
                }
            }
            con.close();
            
            wchars.add(curWorld, chars);
        } catch (SQLException sqle) {
            sqle.printStackTrace();
        }
        
        return wchars;
    }
    
    public void loadAccountCharacters(MapleClient c) {
        Integer accId = c.getAccID();
        int gmLevel = 0;
        boolean firstAccountLogin;
        
        lgnRLock.lock();
        try {
            firstAccountLogin = !accountChars.containsKey(accId);
        } finally {
            lgnRLock.unlock();
        }
        
        if(!firstAccountLogin) {
            Set<Integer> accWorlds = new HashSet<>();
            
            lgnRLock.lock();
            try {
                for(Integer chrid : getAccountCharacterEntries(accId)) {
                    accWorlds.add(worldChars.get(chrid));
                }
            } finally {
                lgnRLock.unlock();
            }
            
            for(Integer aw : accWorlds) {
                for(MapleCharacter chr : worlds.get(aw).getAllCharactersView()) {
                    if(gmLevel < chr.gmLevel()) gmLevel = chr.gmLevel();
                }
            }
            
            c.setGMLevel(gmLevel);
            return;
        }
        
        List<List<MapleCharacter>> accChars = loadAccountCharactersViewFromDb(c, worlds.size());
        
        lgnWLock.lock();
        try {
            Set<Integer> chars = new HashSet<>(5);
            for(int wid = 0; wid < worlds.size(); wid++) {
                World w = worlds.get(wid);
                List<MapleCharacter> wchars = accChars.get(wid);
                w.loadAccountCharactersView(accId, wchars);
                
                for(MapleCharacter chr : wchars) {
                    int cid = chr.getId();
                    if(gmLevel < chr.gmLevel()) gmLevel = chr.gmLevel();
                    
                    chars.add(cid);
                    worldChars.put(cid, wid);
                }
            }
            
            accountChars.put(accId, chars);
        } finally {
            lgnWLock.unlock();
        }
        
        c.setGMLevel(gmLevel);
    }
    
    private static String getRemoteIp(InetSocketAddress isa) {
        return isa.getAddress().getHostAddress();
    }
    
    public void setCharacteridInTransition(InetSocketAddress isa, int charId) {
        String remoteIp = getRemoteIp(isa);
        
        lgnWLock.lock();
        try {
            transitioningChars.put(remoteIp, charId);
        } finally {
            lgnWLock.unlock();
        }
    }
    
    public boolean validateCharacteridInTransition(InetSocketAddress isa, int charId) {
        String remoteIp = getRemoteIp(isa);
        
        lgnWLock.lock();
        try {
            Integer cid = transitioningChars.remove(remoteIp);
            return cid != null && cid.equals(charId);
        } finally {
            lgnWLock.unlock();
        }
    }
    
    public void registerLoginState(MapleClient c) {
        srvLock.lock();
        try {
            inLoginState.put(c, System.currentTimeMillis() + 600000);
        } finally {
            srvLock.unlock();
        }
    }
    
    public void unregisterLoginState(MapleClient c) {
        srvLock.lock();
        try {
            inLoginState.remove(c);
        } finally {
            srvLock.unlock();
        }
    }
    
    private void disconnectIdlesOnLoginState() {
        srvLock.lock();
        try {
            List<MapleClient> toDisconnect = new LinkedList<>();
            long timeNow = System.currentTimeMillis();
            
            for(Entry<MapleClient, Long> mc : inLoginState.entrySet()) {
                if(timeNow > mc.getValue()) {
                    toDisconnect.add(mc.getKey());
                }
            }
            
            for(MapleClient c : toDisconnect) {
                if(c.isLoggedIn()) {
                    c.disconnect(false, false);
                } else {
                    c.getSession().close(true);
                }
                
                inLoginState.remove(c);
            }
        } finally {
            srvLock.unlock();
        }
    }
    
    private void disconnectIdlesOnLoginTask() {
        TimerManager.getInstance().register(new Runnable() {
            @Override
            public void run() {
                disconnectIdlesOnLoginState();
            }
        }, 300000);
    }
    
    public final Runnable shutdown(final boolean restart) {//no player should be online when trying to shutdown!
        return new Runnable() {
            @Override
            public void run() {
                srvLock.lock();
                
                try {
                    System.out.println((restart ? "Restarting" : "Shutting down") + " the server!\r\n");
                    if (getWorlds() == null) return;//already shutdown
                    for (World w : getWorlds()) {
                        w.shutdown();
                    }
                    /*for (World w : getWorlds()) {
                        while (w.getPlayerStorage().getAllCharacters().size() > 0) {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException ie) {
                                System.err.println("FUCK MY LIFE");
                            }
                        }
                    }
                    for (Channel ch : getAllChannels()) {
                        while (ch.getConnectedClients() > 0) {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException ie) {
                                System.err.println("FUCK MY LIFE");
                            }
                        }
                    }*/
                    
                    if(ServerConstants.USE_THREAD_TRACKER) ThreadTracker.getInstance().cancelThreadTrackerTask();

                    TimerManager.getInstance().purge();
                    TimerManager.getInstance().stop();

                    for (Channel ch : getAllChannels()) {
                        while (!ch.finishedShutdown()) {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException ie) {
                                ie.printStackTrace();
                                System.err.println("FUCK MY LIFE");
                            }
                        }
                    }
                    worlds.clear();
                    worlds = null;
                    channels.clear();
                    channels = null;
                    worldRecommendedList.clear();
                    worldRecommendedList = null;

                    System.out.println("Worlds + Channels are offline.");
                    acceptor.unbind();
                    acceptor = null;
                    if (!restart) {
                        System.exit(0);
                    } else {
                        System.out.println("\r\nRestarting the server....\r\n");
                        try {
                            instance.finalize();//FUU I CAN AND IT'S FREE
                        } catch (Throwable ex) {
                            ex.printStackTrace();
                        }
                        instance = null;
                        System.gc();
                        getInstance().init();//DID I DO EVERYTHING?! D:
                    }
                } finally {
                    srvLock.unlock();
                }
            }
        };
    }
}

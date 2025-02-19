package dev.sora.relay.game

import com.google.gson.JsonParser
import com.nukkitx.protocol.bedrock.BedrockPacket
import com.nukkitx.protocol.bedrock.data.*
import com.nukkitx.protocol.bedrock.packet.*
import dev.sora.relay.RakNetRelaySession
import dev.sora.relay.RakNetRelaySessionListener
import dev.sora.relay.cheat.BasicThing.Companion.chat
import dev.sora.relay.cheat.command.CommandManager
import dev.sora.relay.cheat.module.ModuleManager
import dev.sora.relay.cheat.module.impl.combat.ModuleAntiBot.chat
import dev.sora.relay.cheat.module.impl.misc.ModuleChatBypass
import dev.sora.relay.game.entity.EntityPlayerSP
import dev.sora.relay.game.event.EventManager
import dev.sora.relay.game.event.EventPacketInbound
import dev.sora.relay.game.event.EventPacketOutbound
import dev.sora.relay.game.event.EventTick
import dev.sora.relay.game.utils.TimerUtil
import dev.sora.relay.game.utils.movement.MovementUtils
import dev.sora.relay.game.world.WorldClient
import dev.sora.relay.utils.base64Decode
import java.util.*


class GameSession : RakNetRelaySessionListener.PacketListener {

    val thePlayer = EntityPlayerSP()
    val theWorld = WorldClient(this)
    lateinit var moduleManager: ModuleManager
    val eventManager = EventManager()
    var cnMode = true

    lateinit var netSession: RakNetRelaySession
    private val hookedTimer: TimerUtil = TimerUtil()
    var hooked = false
    var xuid = ""
    var identity = UUID.randomUUID().toString()
    var displayName = "Player"

    val netSessionInitialized: Boolean
        get() = this::netSession.isInitialized

    override fun onPacketInbound(packet: BedrockPacket): Boolean {
        val event = EventPacketInbound(this, packet)
        eventManager.emit(event)
        if (event.isCanceled()) {
            return false
        }

        if (packet is StartGamePacket) {
            if(cnMode) {
                event.cancel()
                packet.playerMovementSettings = SyncedPlayerMovementSettings().apply {
                    movementMode = AuthoritativeMovementMode.SERVER
                    rewindHistorySize = 0
                    isServerAuthoritativeBlockBreaking = true
                }
                packet.authoritativeMovementMode = AuthoritativeMovementMode.SERVER
                println("Hooked$packet")
                hookedTimer.reset()
                netSession.inboundPacket(packet)
                return false
            }
            thePlayer.entityId = packet.runtimeEntityId
            theWorld.entityMap.clear()
        } else if (packet is RespawnPacket) {
            thePlayer.entityId = packet.runtimeEntityId
        }
        if (hookedTimer.delay(15000f) && !hooked) {
                if (MovementUtils.isMoving(this)) netSession.inboundPacket(TextPacket().apply {
                    type = TextPacket.Type.RAW
                    isNeedsTranslation = false
                    message = "[§9§lProtoHax§r] Hooked StartGamePacket, Welcome!"
                    xuid = ""
                    sourceName = ""
                })
                hooked = true

        }
        thePlayer.onPacket(packet)
        theWorld.onPacket(packet)
        return true
    }

    override fun onPacketOutbound(packet: BedrockPacket): Boolean {
        val event = EventPacketOutbound(this, packet)
        eventManager.emit(event)
        if (event.isCanceled()) {
            return false
        }

        if (packet is LoginPacket) {
            val body = JsonParser.parseString(packet.chainData.toString()).asJsonObject.getAsJsonArray("chain")
            for (chain in body) {
                val chainBody =
                    JsonParser.parseString(base64Decode(chain.asString.split(".")[1]).toString(Charsets.UTF_8)).asJsonObject
                if (chainBody.has("extraData")) {
                    val xData = chainBody.getAsJsonObject("extraData")
                    xuid = xData.get("XUID").asString
                    identity = xData.get("identity").asString
                    displayName = xData.get("displayName").asString
                }
            }
        } else if (packet is PlayerAuthInputPacket) {
            if(cnMode) {
                if (!moduleManager.getModuleByName("FreeCam")!!.state) {
                    netSession.outboundPacket(MovePlayerPacket().apply {
                        runtimeEntityId = thePlayer.entityId
                        position = packet.position
                        rotation = packet.rotation
                        mode = MovePlayerPacket.Mode.NORMAL
                        isOnGround = (thePlayer.motionY == 0.0)
                        ridingRuntimeEntityId = 0
                        entityType = 0
                        tick = packet.tick
                    })
                }
            }
            /*if(packet.playerActions.isNotEmpty())chat(this,"Actions: "+packet.playerActions)
            for (playerAction in packet.playerActions) {
                netSession.outboundPacket(PlayerActionPacket().apply {
                    runtimeEntityId=thePlayer.entityId
                    action=playerAction.action
                    blockPosition=playerAction.blockPosition
                })
            }*/
        }
        if(packet !is MovePlayerPacket && cnMode) thePlayer.handleClientPacket(packet, this)
        return true
    }

    fun onTick() {
        eventManager.emit(EventTick(this))
    }

    fun sendPacket(packet: BedrockPacket) {
        val event = EventPacketOutbound(this, packet)
        eventManager.emit(event)
        if (event.isCanceled()) {
            return
        }
        /*val chatBypass = moduleManager.getModuleByName("ChatBypass") as ModuleChatBypass
        if(event.packet is TextPacket && chatBypass.state){
            event.packet.message=chatBypass.getStr(event.packet.message)
        }*/
        netSession.outboundPacket(packet)
    }

    fun sendPacketToClient(packet: BedrockPacket) {
        val event = EventPacketInbound(this, packet)
        eventManager.emit(event)
        if (event.isCanceled()) {
            return
        }
        netSession.outboundPacket(packet)
    }
}

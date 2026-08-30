package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.settings.CONNECTION_MODE_TUNNEL
import com.uacspoofer.mobile.ui.theme.UacColors

private data class SettingField(val key:String,val label:String,val value:String,val secret:Boolean=false)

@Composable internal fun AdvancedSettingsScreen(onBackClick:()->Unit) {
    val context=LocalContext.current
    val store=remember(context){AdvancedSettingsStore(context)}
    var data by remember{mutableStateOf(store.snapshot())}
    var text by remember{mutableStateOf(toText(data))}
    var notice by remember{mutableStateOf("Changes are applied on the next connection")}
    var resetDialog by remember{mutableStateOf(false)}
    val accent=UacColors.DisconnectedBlue
    fun update(k:String,v:String){text=text.toMutableMap().also{it[k]=v}}
    fun fields(vararg pairs:Pair<String,String>)=pairs.map{SettingField(it.first,it.second,text[it.first].orEmpty(),it.first=="trojanPassword")}
    ToolPageScaffold(
        accent = accent,
        header = {
            ToolPageHeader(
                title = homeText("Advanced Settings", "تنظیمات پیشرفته"),
                subtitle = homeText("Complete runtime connection controls", "کنترل کامل تنظیمات اتصال"),
                icon = Icons.Outlined.Tune,
                accent = accent,
                onMenuClick = onBackClick,
                navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                navigationDescription = homeText("Back to settings", "برگشت به تنظیمات"),
            )
        },
    ) {
        item{ConnectionModeSelector(data.connectionMode){data=data.copy(connectionMode=it);notice="Changes are applied on the next connection"}}
        item{Group("Primary edge",fields("primaryAddress" to "Address","primaryPort" to "Port","primaryMaxSplit" to "Max split"),::update)}
        item{Group("Irancell edge",fields("irancellAddress" to "Address","irancellPort" to "Port","irancellMaxSplit" to "Max split"),::update)}
        item{Group("Fallback edge",fields("fallbackAddress" to "Address","fallbackPort" to "Port","fallbackMaxSplit" to "Max split"),::update)}
        item{Group("Core protocol",fields("outboundProtocol" to "Protocol","transportNetwork" to "Transport","transportSecurity" to "Security"),::update)}
        item{Group("Trojan · TLS · WebSocket",fields("trojanPassword" to "Trojan password","tlsSni" to "TLS SNI","tlsAlpn" to "TLS ALPN","tlsFingerprint" to "Fingerprint","wsHost" to "WS Host","wsPath" to "WS Path"),::update)}
        item{Group("Finalmask fragmentation",fields("finalmaskPacket" to "Packet","finalmaskLength" to "Length","finalmaskDelayMs" to "Delay (ms)"),::update)}
        item{Group("Routing & keepalive",fields("domainStrategy" to "Domain strategy","routingDomainStrategy" to "Routing strategy","keepAliveIdleSeconds" to "Idle (sec)","keepAliveIntervalSeconds" to "Interval (sec)"),::update)}
        item{Group("SOCKS",fields("socksAddress" to "Address","socksPort" to "Port"),::update)}
        item{Group("TUN & DNS",fields("tunMtu" to "MTU","tunAddress" to "TUN address","nativeDns" to "DNS trap address","dnsResolverUrl" to "Preferred DoH resolver","tunRoute" to "Route"),::update)}
        item{Column(Modifier.fillMaxWidth().background(ToolCardBrush,ToolCardShape).border(1.dp,Color.White.copy(.08f),ToolCardShape).padding(14.dp)){SectionLabel("Transport switches");Toggle("Mux",data.muxEnabled){data=data.copy(muxEnabled=it)};if(data.muxEnabled) MiniField("Mux concurrency",text["muxConcurrency"].orEmpty(),change={update("muxConcurrency",it)});Toggle("IPv4 only",data.ipv4Only){data=data.copy(ipv4Only=it)};Toggle("Block QUIC (UDP/443)",data.blockUdp443){data=data.copy(blockUdp443=it)};Toggle("SOCKS UDP",data.socksUdp){data=data.copy(socksUdp=it)}}}
        item{Text(notice,color=if(notice.startsWith("Check"))UacColors.ErrorRed else accent,fontSize=12.sp,modifier=Modifier.fillMaxWidth().background(accent.copy(.08f),RoundedCornerShape(12.dp)).border(1.dp,accent.copy(.2f),RoundedCornerShape(12.dp)).padding(12.dp))}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedButton({resetDialog=true},Modifier.weight(1f)){Icon(Icons.Outlined.RestartAlt,null);Spacer(Modifier.width(5.dp));Text("Reset")};Button({val parsed=parse(text,data);if(parsed==null)notice="Check numeric fields: ports, splits and MTU" else {data=store.save(parsed);text=toText(data);notice="Saved — used on the next connection"}},Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=accent)){Icon(Icons.Outlined.Save,null);Spacer(Modifier.width(5.dp));Text("Save")}}}
        item{Spacer(Modifier.height(20.dp))}
    }
    if(resetDialog)AlertDialog(onDismissRequest={resetDialog=false},title={Text("Reset all settings?")},text={Text("Restores the tested defaults for every runtime setting.")},confirmButton={TextButton({data=store.resetDefaults();text=toText(data);notice="All defaults restored — used on the next connection";resetDialog=false}){Text("Reset",color=UacColors.ErrorRed)}},dismissButton={TextButton({resetDialog=false}){Text("Cancel")}})
}

@Composable private fun Group(title:String,fields:List<SettingField>,change:(String,String)->Unit){Column(Modifier.fillMaxWidth().background(ToolCardBrush,ToolCardShape).border(1.dp,Color.White.copy(.08f),ToolCardShape).padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){SectionLabel(title);fields.chunked(2).forEach{r->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){r.forEach{f->MiniField(f.label,f.value,{change(f.key,it)},f.secret,Modifier.weight(1f))};if(r.size==1)Spacer(Modifier.weight(1f))}}}}
@Composable private fun MiniField(label:String,value:String,change:(String)->Unit,secret:Boolean=false,modifier:Modifier=Modifier){OutlinedTextField(value,change,modifier=modifier,label={Text(label)},singleLine=true,visualTransformation=if(secret)PasswordVisualTransformation()else VisualTransformation.None,colors=OutlinedTextFieldDefaults.colors(focusedTextColor=Color.White,unfocusedTextColor=Color.White,focusedBorderColor=UacColors.DisconnectedBlue,unfocusedBorderColor=Color.White.copy(.14f)))}
@Composable private fun Toggle(label:String,v:Boolean,change:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,color=Color.White,modifier=Modifier.padding(top=12.dp));Switch(v,change)}}

@Composable private fun ConnectionModeSelector(mode:String,change:(String)->Unit){Column(Modifier.fillMaxWidth().background(ToolCardBrush,ToolCardShape).border(1.dp,Color.White.copy(.08f),ToolCardShape).padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){SectionLabel("Connection mode");Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){ModeButton("Tunnel",mode==CONNECTION_MODE_TUNNEL,{change(CONNECTION_MODE_TUNNEL)},Modifier.weight(1f));ModeButton("Proxy",mode==CONNECTION_MODE_PROXY,{change(CONNECTION_MODE_PROXY)},Modifier.weight(1f))};Text(if(mode==CONNECTION_MODE_PROXY)"Local SOCKS5 only · no device VPN route · apps must use the SOCKS address below" else "System-wide VPN route for the whole device",color=UacColors.TextSecondary,fontSize=11.sp)}}
@Composable private fun ModeButton(label:String,selected:Boolean,click:()->Unit,modifier:Modifier=Modifier){if(selected)Button(click,modifier=modifier,colors=ButtonDefaults.buttonColors(containerColor=UacColors.DisconnectedBlue)){Text(label)}else OutlinedButton(click,modifier=modifier){Text(label)}}


private fun toText(d:AdvancedSettingsData)=mutableMapOf("primaryAddress" to d.primaryAddress,"primaryPort" to "${d.primaryPort}","primaryMaxSplit" to "${d.primaryMaxSplit}","irancellAddress" to d.irancellAddress,"irancellPort" to "${d.irancellPort}","irancellMaxSplit" to "${d.irancellMaxSplit}","fallbackAddress" to d.fallbackAddress,"fallbackPort" to "${d.fallbackPort}","fallbackMaxSplit" to "${d.fallbackMaxSplit}","telegramAddress" to d.telegramAddress,"telegramFallbackAddress" to d.telegramFallbackAddress,"telegramPort" to "${d.telegramPort}","telegramMaxSplit" to "${d.telegramMaxSplit}","telegramFingerprint" to d.telegramFingerprint,"telegramCipherSuites" to d.telegramCipherSuites,"outboundProtocol" to d.outboundProtocol,"transportNetwork" to d.transportNetwork,"transportSecurity" to d.transportSecurity,"trojanPassword" to d.trojanPassword,"tlsSni" to d.tlsSni,"tlsAlpn" to d.tlsAlpn,"tlsFingerprint" to d.tlsFingerprint,"wsHost" to d.wsHost,"wsPath" to d.wsPath,"finalmaskPacket" to d.finalmaskPacket,"finalmaskLength" to "${d.finalmaskLength}","finalmaskDelayMs" to "${d.finalmaskDelayMs}","muxConcurrency" to "${d.muxConcurrency}","domainStrategy" to d.domainStrategy,"routingDomainStrategy" to d.routingDomainStrategy,"keepAliveIdleSeconds" to "${d.keepAliveIdleSeconds}","keepAliveIntervalSeconds" to "${d.keepAliveIntervalSeconds}","socksAddress" to d.socksAddress,"socksPort" to "${d.socksPort}","tunMtu" to "${d.tunMtu}","tunAddress" to d.tunAddress,"nativeDns" to d.nativeDns,"dnsResolverUrl" to d.dnsResolverUrl,"tunRoute" to d.tunRoute)
private fun parse(m:Map<String,String>,d:AdvancedSettingsData):AdvancedSettingsData?=runCatching{d.copy(primaryAddress=m.getValue("primaryAddress"),primaryPort=m.getValue("primaryPort").toInt(),primaryMaxSplit=m.getValue("primaryMaxSplit").toInt(),irancellAddress=m.getValue("irancellAddress"),irancellPort=m.getValue("irancellPort").toInt(),irancellMaxSplit=m.getValue("irancellMaxSplit").toInt(),fallbackAddress=m.getValue("fallbackAddress"),fallbackPort=m.getValue("fallbackPort").toInt(),fallbackMaxSplit=m.getValue("fallbackMaxSplit").toInt(),telegramAddress=m.getValue("telegramAddress"),telegramFallbackAddress=m.getValue("telegramFallbackAddress"),telegramPort=m.getValue("telegramPort").toInt(),telegramMaxSplit=m.getValue("telegramMaxSplit").toInt(),telegramFingerprint=m.getValue("telegramFingerprint"),telegramCipherSuites=m.getValue("telegramCipherSuites"),outboundProtocol=m.getValue("outboundProtocol"),transportNetwork=m.getValue("transportNetwork"),transportSecurity=m.getValue("transportSecurity"),trojanPassword=m.getValue("trojanPassword"),tlsSni=m.getValue("tlsSni"),tlsAlpn=m.getValue("tlsAlpn"),tlsFingerprint=m.getValue("tlsFingerprint"),wsHost=m.getValue("wsHost"),wsPath=m.getValue("wsPath"),finalmaskPacket=m.getValue("finalmaskPacket"),finalmaskLength=m.getValue("finalmaskLength").toInt(),finalmaskDelayMs=m.getValue("finalmaskDelayMs").toInt(),muxConcurrency=m.getValue("muxConcurrency").toInt(),domainStrategy=m.getValue("domainStrategy"),routingDomainStrategy=m.getValue("routingDomainStrategy"),keepAliveIdleSeconds=m.getValue("keepAliveIdleSeconds").toInt(),keepAliveIntervalSeconds=m.getValue("keepAliveIntervalSeconds").toInt(),socksAddress=m.getValue("socksAddress"),socksPort=m.getValue("socksPort").toInt(),tunMtu=m.getValue("tunMtu").toInt(),tunAddress=m.getValue("tunAddress"),nativeDns=m.getValue("nativeDns"),dnsResolverUrl=m.getValue("dnsResolverUrl"),tunRoute=m.getValue("tunRoute")).validated()}.getOrNull()

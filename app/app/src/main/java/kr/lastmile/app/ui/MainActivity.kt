package kr.lastmile.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch
import kr.lastmile.app.BuildConfig
import kr.lastmile.app.LastMileApp
import kr.lastmile.app.R
import kr.lastmile.app.databinding.ActivityMainBinding
import kr.lastmile.app.domain.Destination
import kr.lastmile.app.domain.GearPosition
import kr.lastmile.app.domain.TripEngine
import kr.lastmile.app.domain.TripState
import kr.lastmile.app.domain.UiState
import kr.lastmile.app.service.LastMileService
import kr.lastmile.app.source.MockLocationSource
import kr.lastmile.app.source.MockNaviSource
import kr.lastmile.app.source.MockVehicleSignalSource
import kr.lastmile.app.speech.TextVoiceInput

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var engine: TripEngine
    private val tiles = mutableMapOf<String, View>()
    private var shownPage: View? = null
    private var uxRestricted = false
    private var noKeyboard = false
    private var ux: UxRestrictions? = null
    private var versionTaps = 0
    private var lastState: TripState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        engine = (application as LastMileApp).engine
        startForegroundService(Intent(this, LastMileService::class.java))

        buildTiles()
        bindIdle(); bindDrive(); bindArrived(); bindVoice()
        b.settingsButton.setOnClickListener { showSettings() }
        Pressable.attach(b.settingsButton)

        ux = UxRestrictions(this) { restricted, noKb -> runOnUiThread { uxRestricted = restricted; noKeyboard = noKb; render(engine.ui.value) } }.also { it.start() }

        lifecycleScope.launch { engine.ui.collect { render(it) } }
    }

    override fun onDestroy() { ux?.stop(); super.onDestroy() }

    // ---------- IDLE ----------
    private fun buildTiles() {
        val grid = b.pageIdle.root.findViewById<GridLayout>(R.id.tileGrid)
        val cols = resources.getInteger(R.integer.tile_columns)
        grid.columnCount = cols
        for ((i, d) in engine.repository.all().withIndex()) {
            val v = LayoutInflater.from(this).inflate(R.layout.view_dest_tile, grid, false)
            v.findViewById<TextView>(R.id.tileName).text = d.name
            v.findViewById<TextView>(R.id.tileCategory).text = d.category
            v.findViewById<ImageView>(R.id.tileIcon).setImageResource(iconFor(d.category))
            val lp = GridLayout.LayoutParams(GridLayout.spec(i / cols, 1f), GridLayout.spec(i % cols, 1f))
            lp.width = 0; lp.setMargins(if (i % cols == 0) 0 else 12, if (i / cols == 0) 0 else 12, if (i % cols == cols - 1) 0 else 12, 12)
            grid.addView(v, lp)
            v.setOnClickListener { engine.selectDestination(d) }
            Pressable.attach(v)
            tiles[d.id] = v
        }
    }

    private fun iconFor(category: String) = when (category) {
        "식당" -> R.drawable.ic_restaurant; "병원" -> R.drawable.ic_hospital; "공항" -> R.drawable.ic_flight
        "학교" -> R.drawable.ic_school; "아파트" -> R.drawable.ic_home; else -> R.drawable.ic_pin
    }

    private fun bindIdle() {
        val root = b.pageIdle.root
        val start = root.findViewById<Button>(R.id.startButton)
        start.setOnClickListener { engine.startGuidance() }
        val sw = root.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.meetingSwitch)
        sw.setOnClickListener { engine.setMeetingMode(sw.isChecked) }
        root.findViewById<View>(R.id.meetingCard).setOnClickListener { if (engine.ui.value.recipientPhone.isBlank()) showSettings() else { sw.toggle(); engine.setMeetingMode(sw.isChecked) } }
        Pressable.attach(start)
    }

    // ---------- DRIVE ----------
    private fun bindDrive() {
        val root = b.pageDrive.root
        val ask = root.findViewById<Button>(R.id.askButton); val end = root.findViewById<Button>(R.id.endButton)
        ask.setOnClickListener { engine.askVoice() }
        end.setOnClickListener { engine.endGuidance() }
        Pressable.attach(ask, end)
        fact(root, R.id.factCurb, R.drawable.ic_stop_sign, getString(R.string.parking_curb))
        fact(root, R.id.factValet, R.drawable.ic_person, getString(R.string.parking_valet))
        fact(root, R.id.factParking, R.drawable.ic_parking, getString(R.string.parking_lot))
    }

    private fun fact(root: View, id: Int, icon: Int, label: String) {
        val row = root.findViewById<View>(id)
        row.findViewById<ImageView>(R.id.factIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.factLabel).text = label
    }
    private fun setFact(root: View, id: Int, value: String) { root.findViewById<View>(id).findViewById<TextView>(R.id.factValue).text = value }

    // ---------- ARRIVED ----------
    private fun bindArrived() {
        val done = b.pageArrived.root.findViewById<Button>(R.id.doneButton)
        done.setOnClickListener { engine.reset() }
        Pressable.attach(done)
    }

    // ---------- VOICE ----------
    private fun bindVoice() {
        val v = b.voiceSheet.root
        v.findViewById<View>(R.id.voiceClose).setOnClickListener { engine.cancelVoice() }
        v.setOnClickListener { engine.cancelVoice() }
        val typed = v.findViewById<android.widget.EditText>(R.id.voiceTyped)
        val send = v.findViewById<Button>(R.id.voiceSend)
        val submit = { val t = typed.text.toString(); if (t.isNotBlank()) { engine.submitUtterance(t); typed.setText("") } }
        send.setOnClickListener { submit() }
        typed.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        Pressable.attach(send, v.findViewById(R.id.voiceClose))
    }

    // ---------- RENDER ----------
    private fun render(s: UiState) {
        // state chip
        b.stateChip.text = getString(when (s.state) {
            TripState.IDLE -> R.string.state_idle; TripState.ARMED -> R.string.state_armed
            TripState.STOPPING -> R.string.state_stopping; TripState.ARRIVED, TripState.DONE -> R.string.state_arrived
            else -> R.string.state_driving
        })
        b.stateChip.setBackgroundResource(if (s.state == TripState.IDLE) R.drawable.bg_chip else R.drawable.bg_chip_accent)

        val page = when (s.state) { TripState.IDLE -> b.pageIdle.root; TripState.ARRIVED, TripState.DONE -> b.pageArrived.root; else -> b.pageDrive.root }
        showPage(page, animate = lastState != null && lastState != s.state)
        lastState = s.state

        // IDLE
        for ((id, v) in tiles) v.isSelected = s.selected?.id == id
        for ((id, v) in tiles) { val sel = s.selected?.id == id; val c = if (sel) getColor(R.color.text_on_primary) else getColor(R.color.text_primary)
            v.findViewById<TextView>(R.id.tileName).setTextColor(c); v.findViewById<TextView>(R.id.tileCategory).setTextColor(if (sel) getColor(R.color.basic_500) else getColor(R.color.text_secondary)); v.findViewById<ImageView>(R.id.tileIcon).setColorFilter(c) }
        b.pageIdle.root.findViewById<Button>(R.id.startButton).isEnabled = s.selected != null
        val sw = b.pageIdle.root.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.meetingSwitch)
        sw.isChecked = s.meetingMode; sw.isEnabled = s.recipientPhone.isNotBlank()
        b.pageIdle.root.findViewById<TextView>(R.id.meetingDesc).text = when {
            s.recipientPhone.isBlank() -> getString(R.string.meeting_no_recipient)
            s.meetingMode -> getString(R.string.meeting_on_fmt, s.recipientName.ifBlank { s.recipientPhone })
            else -> getString(R.string.meeting_off)
        }

        // DRIVE
        val d = s.destination; val dr = b.pageDrive.root
        dr.findViewById<TextView>(R.id.driveDestination).text = d?.name ?: ""
        dr.findViewById<TextView>(R.id.driveRemain).text = when {
            s.remainMeters < 0 -> "—"
            s.remainMeters >= 1000 -> getString(R.string.remain_fmt_km, s.remainMeters / 1000.0)
            else -> getString(R.string.remain_fmt_m, s.remainMeters)
        }
        val eta = dr.findViewById<TextView>(R.id.driveEta)
        eta.visibility = if (s.remainSec > 0) View.VISIBLE else View.INVISIBLE
        eta.text = getString(R.string.eta_fmt, maxOf(1, s.remainSec / 60))
        dr.findViewById<TextView>(R.id.driveGuidance).text = s.lastSpoken.ifBlank { if (s.state == TripState.ARMED) "내비 안내를 기다리고 있어요" else "" }
        val banner = dr.findViewById<View>(R.id.stoppingBanner)
        banner.visibility = if (s.state == TripState.STOPPING) View.VISIBLE else View.GONE
        if (d != null) {
            dr.findViewById<TextView>(R.id.stoppingHint).text = if (d.curbsideAllowed) "여기는 ${d.curbsideLimitMin}분까지 세울 수 있어요" else "정차 금지 구역이에요 · ${d.parkingName}로 안내"
            setFact(dr, R.id.factCurb, if (d.curbsideAllowed) "정문 앞 ${d.curbsideLimitMin}분까지" else "정문 앞 정차 금지")
            setFact(dr, R.id.factValet, if (d.valet) "있음" else "없음")
            setFact(dr, R.id.factParking, listOf(d.parkingName, d.parkingFee).filter { it.isNotBlank() }.joinToString(" · "))
        }
        val ms = dr.findViewById<TextView>(R.id.meetingStatusText)
        ms.text = when {
            !s.meetingMode -> "꺼져 있어요"
            "arrived" in s.messagesSent -> "도착 메시지 보냄 · ${s.lastMessage}"
            "eta" in s.messagesSent -> "5분 전 알림 보냄 · 도착하면 한 번 더 보내요"
            else -> "${s.recipientName.ifBlank { s.recipientPhone }}에게 5분 전·도착 시 자동 전송"
        }

        // ARRIVED
        val ar = b.pageArrived.root
        ar.findViewById<TextView>(R.id.arrivedDestination).text = d?.name ?: ""
        ar.findViewById<TextView>(R.id.arrivedNote).text = d?.let { listOf(it.parkingName, it.parkingFee).filter { f -> f.isNotBlank() }.joinToString(" · ") } ?: ""
        val card = ar.findViewById<View>(R.id.arrivedMessageCard)
        card.visibility = if ("arrived" in s.messagesSent) View.VISIBLE else View.GONE
        ar.findViewById<TextView>(R.id.arrivedMessageText).text = "${s.recipientName.ifBlank { s.recipientPhone }}에게 보냄 · ${s.lastMessage}"

        // VOICE sheet
        val vs = b.voiceSheet.root
        val wantVoice = s.listening || (s.lastUtterance.isNotBlank() && vs.visibility == View.VISIBLE)
        vs.visibility = if (wantVoice) View.VISIBLE else View.GONE
        vs.findViewById<TextView>(R.id.voiceTitle).text = if (s.listening) getString(R.string.listening) else "\"${s.lastUtterance}\""
        val ans = vs.findViewById<TextView>(R.id.voiceAnswer)
        ans.visibility = if (!s.listening && s.lastUtterance.isNotBlank()) View.VISIBLE else View.GONE
        ans.text = s.lastSpoken
        val typedRow = vs.findViewById<View>(R.id.voiceTypedRow)
        typedRow.visibility = if (s.listening && engine.voice is TextVoiceInput && !noKeyboard) View.VISIBLE else View.GONE
        if (!s.listening && s.lastUtterance.isNotBlank() && vs.visibility == View.VISIBLE) vs.postDelayed({ if (!engine.ui.value.listening) vs.visibility = View.GONE }, 5000)
    }

    private fun showPage(page: View, animate: Boolean) {
        if (shownPage === page) return
        val prev = shownPage; shownPage = page
        page.alpha = if (animate) 0f else 1f; page.visibility = View.VISIBLE
        if (animate) page.animate().alpha(1f).setDuration(200).start()
        for (p in listOf(b.pageIdle.root, b.pageDrive.root, b.pageArrived.root)) if (p !== page) p.visibility = View.GONE
        prev?.alpha = 1f
    }

    // ---------- SETTINGS / DEV ----------
    private fun showSettings() {
        val dlg = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_settings, null)
        val name = v.findViewById<android.widget.EditText>(R.id.recipientName); val phone = v.findViewById<android.widget.EditText>(R.id.recipientPhone)
        name.setText(engine.ui.value.recipientName); phone.setText(engine.ui.value.recipientPhone)
        val locked = noKeyboard
        name.isEnabled = !locked; phone.isEnabled = !locked
        v.findViewById<View>(R.id.restrictedNote).visibility = if (locked) View.VISIBLE else View.GONE
        val save = v.findViewById<Button>(R.id.saveButton)
        save.setOnClickListener { engine.setRecipient(name.text.toString(), phone.text.toString()); dlg.dismiss() }
        val ver = v.findViewById<TextView>(R.id.versionText)
        ver.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME) + if (BuildConfig.USE_MOCK) " · mock" else ""
        ver.setOnClickListener { if (++versionTaps >= 5) { versionTaps = 0; dlg.dismiss(); showDev() } }
        Pressable.attach(save)
        dlg.setContentView(v); dlg.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED; dlg.show()
    }

    private fun showDev() {
        val dlg = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_dev, null)
        val navi = engine.navi as? MockNaviSource; val veh = engine.vehicle as? MockVehicleSignalSource; val loc = engine.location as? MockLocationSource
        v.findViewById<View>(R.id.devMockPanel).visibility = if (navi != null) View.VISIBLE else View.GONE
        val label = v.findViewById<TextView>(R.id.distanceLabel)
        v.findViewById<SeekBar>(R.id.distanceSeek).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { label.text = "남은 거리 ${p}m"; if (fromUser) navi?.setRemainDistance(p) }
            override fun onStartTrackingTouch(sb: SeekBar) {}; override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        v.findViewById<View>(R.id.btn3km).setOnClickListener { navi?.setRemainDistance(2900) }
        v.findViewById<View>(R.id.btn1km).setOnClickListener { navi?.setRemainDistance(950) }
        v.findViewById<View>(R.id.btn300m).setOnClickListener { navi?.setRemainDistance(250) }
        v.findViewById<View>(R.id.btnNaviArrived).setOnClickListener { navi?.arrive() }
        v.findViewById<View>(R.id.btnBrake).setOnClickListener { veh?.brake(true) }
        v.findViewById<View>(R.id.btnGearD).setOnClickListener { veh?.gear(GearPosition.D) }
        v.findViewById<View>(R.id.btnGearP).setOnClickListener { veh?.gear(GearPosition.P) }
        v.findViewById<View>(R.id.btnGeofence).setOnClickListener { loc?.enter() }
        val src = v.findViewById<TextView>(R.id.devSources); val log = v.findViewById<TextView>(R.id.devLog)
        val job = lifecycleScope.launch { engine.ui.collect { s -> src.text = s.sources.entries.joinToString("  ·  ") { "${it.key}: ${it.value}" }; log.text = s.log.joinToString("\n") } }
        dlg.setOnDismissListener { job.cancel() }
        dlg.setContentView(v); dlg.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED; dlg.show()
    }
}

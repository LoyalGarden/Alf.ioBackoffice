/**
 * This file is part of alf.io backoffice.

 * alf.io backoffice is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * alf.io backoffice is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with alf.io backoffice.  If not, see //www.gnu.org/licenses/>.
 */
package alfio.backoffice

import alfio.backoffice.model.AlfioConfiguration
import alfio.backoffice.model.CheckInStatus.*
import alfio.backoffice.model.Ticket
import alfio.backoffice.task.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Vibrator
import android.support.design.widget.Snackbar
import android.util.Log
import android.view.Gravity
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.WindowManager
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import kotlinx.android.synthetic.main.activity_event_detail.*
import kotlinx.android.synthetic.main.content_event_detail.*
import kotlinx.android.synthetic.main.event_descriptor.*
import kotlin.properties.Delegates

class EventDetailActivity : BaseActivity() {

    var config: AlfioConfiguration by Delegates.notNull();
    var ticket: Ticket? = null;
    var qrCode: String? = null;
    var vibratorService by Delegates.notNull<Vibrator>();
    var sensorManager by Delegates.notNull<SensorManager>();
    var accelerometer by Delegates.notNull<Sensor>();
    var shakeDetector by Delegates.notNull<ShakeDetector>();

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);
        vibratorService = getSystemService(VIBRATOR_SERVICE) as Vibrator;
        setContentView(R.layout.activity_event_detail);
        setSupportActionBar(toolbar);
        config = intent.extras.get("config") as AlfioConfiguration;
        eventDetailSection.visibility = GONE;
        progressIndicator.visibility = VISIBLE;
        supportActionBar!!.setDisplayHomeAsUpEnabled(true);
        EventDetailLoader(this, false)
                .then(success = { eventLoaded(it, savedInstanceState); }, error = { param, result ->
                    loadingIndicator.visibility = GONE;
                    loadingDataFailed.visibility = VISIBLE;
                })
                .execute(EventDetailParam(config.url, config.eventName));
        requestPermissionForAction(listOf(android.Manifest.permission.DISABLE_KEYGUARD), {window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)});
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager;
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        shakeDetector = ShakeDetector(object: OnShakeListener {
            override fun onShake(count: Int) {
                Log.d(EventDetailActivity@javaClass.simpleName, "shake count $count");
                if(count >= 2) {
                    requestScan();
                }
            }
        });

    }

    private fun eventLoaded(eventDetail: EventDetailResult, savedInstanceState: Bundle?) {
        val drawable = BitmapDrawable(resources, BitmapFactory.decodeByteArray(eventDetail.image, 0, eventDetail.image.size));
        drawable.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL;
        eventLogoContainer.background = drawable;
        val event = eventDetail.event!!;
        eventName.text = event.name;
        writeEventDetails(event, config, eventDates, eventDescription, userDetail, baseUrl, eventName);
        initScan.setOnClickListener { view ->
            requestScan();
        };
        confirm.setOnClickListener { view ->
            confirmCheckIn(qrCode!!);
        }
        displayTicketDetails(savedInstanceState?.get("ticket") as Ticket?, savedInstanceState?.get("qrCode") as String?);
        progressIndicator.visibility = GONE;
        eventDetailSection.visibility = VISIBLE;
    }

    private fun requestScan() {
        requestPermissionForAction(listOf(android.Manifest.permission.VIBRATE), {vibratorService.vibrate(50)}, false);
        scanQRCode(R.string.message_scan_attendee_badge)();
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, i: Intent?) {
        val scanResult : IntentResult? = IntentIntegrator.parseActivityResult(requestCode, resultCode, i);
        if(scanResult != null && scanResult.contents != null) {
            TicketDetailLoader(this)
                    .then({
                        displayTicketDetails(it.ticket!!, scanResult.contents);
                    }, {param, result ->
                        if(result != null) {
                            displayTicketDetails(result.ticket, scanResult.contents);
                        }
                        errorHandler(param, result);
                    }).execute(TicketDetailParam(config, scanResult.contents));
        }
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        super.onSaveInstanceState(outState);
        if(ticket != null) {
            outState?.putSerializable("ticket", ticket);
        }
    }

    override fun onResume() {
        super.onResume();
        sensorManager.registerListener(shakeDetector, accelerometer, SensorManager.SENSOR_DELAY_UI);
    }

    override fun onPause() {
        super.onPause();
        sensorManager.unregisterListener(shakeDetector);
    }

    private fun displayTicketDetails(ticket: Ticket?, qrCode: String?) {
        if(ticket != null) {
            attendeeName.text = ticket.fullName;
            attendeeEmail.text = ticket.email;
            reservationId.text = ticket.ticketsReservationId;
            resultCard.visibility = VISIBLE;
            initCheckInCard.visibility = GONE;
            ticketDetailButtons.visibility = VISIBLE;
            errorCard.visibility = GONE;
            this.ticket = ticket;
            this.qrCode = qrCode;
        } else {
            resultCard.visibility = GONE;
            initCheckInCard.visibility = VISIBLE;
        }
    }

    private fun confirmCheckIn(qrCode: String) {
        performCheckIn.invoke(qrCode, CheckInConfirmation(this), config);
    }

    private fun confirmDeskPayment(qrCode: String) {
        performCheckIn.invoke(qrCode, DeskPaymentConfirmation(this), config);
    }

    private val performCheckIn : (String, CheckInConfirmation, AlfioConfiguration) -> Unit = {qrCode, task, config -> task.then(success = successHandler, error = errorHandler).execute(TicketDetailParam(config, qrCode));}

    private val successHandler : (TicketDetailResult) -> Unit = { result ->
        this.ticket = null;//reset ticket view
        errorCard.visibility = GONE;
        displayTicketDetails(null, null);
        signalSuccess();
        Snackbar.make(initCheckInCard, R.string.message_check_in_successful, Snackbar.LENGTH_LONG).setAction(R.string.message_dismiss, {}).show();
    }

    private val errorHandler: (TicketDetailParam?, TicketDetailResult?) -> Unit = { param, result ->
        ticketDetailButtons.visibility = GONE;
        signalFailure();
        errorButton2.visibility = GONE;
        when(result?.status) {
            MUST_PAY -> {
                errorMessage.text = getString(R.string.checkin_error_must_pay).format(result!!.dueAmount, result.currency);
                errorButton2.visibility = VISIBLE;
                errorButton2.text = getString(R.string.check_in);
                errorButton2.setOnClickListener { confirmDeskPayment(qrCode!!); }
            }
            EMPTY_TICKET_CODE, INVALID_TICKET_CODE -> errorMessage.text = getString(R.string.checkin_error_invalid_code);
            TICKET_NOT_FOUND, EVENT_NOT_FOUND, INVALID_TICKET_STATE -> errorMessage.text = getString(R.string.checkin_error_ticket_not_found);
            else -> {
                errorMessage.text = getString(R.string.message_already_checked_in);
            }
        }
        errorButton1.setOnClickListener { requestScan(); }
        errorCard.visibility = VISIBLE;
    };

    private fun signalSuccess() {
        requestPermissionForAction(listOf(android.Manifest.permission.VIBRATE), {vibratorService.vibrate(200L)}, false);
    }

    private fun signalFailure() {
        requestPermissionForAction(listOf(android.Manifest.permission.VIBRATE), {vibratorService.vibrate(longArrayOf(10L,150L,200L,150L,200L,150L), -1)}, false);
    }
}

//thanks to Jason McReynolds: http://jasonmcreynolds.com/?p=388
class ShakeDetector(val listener: OnShakeListener) : SensorEventListener {

    private var lastShakeTimestamp = 0L;
    private var shakeCount = 0;

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gx = event.values[0].div(SensorManager.GRAVITY_EARTH).toDouble();
        val gy = event.values[1].div(SensorManager.GRAVITY_EARTH).toDouble();
        val gz = event.values[2].div(SensorManager.GRAVITY_EARTH).toDouble();

        val gForce = Math.sqrt(Math.pow(gx, 2.0) + Math.pow(gy, 2.0) + Math.pow(gz, 2.0));

        val now = System.currentTimeMillis();

        if(gForce > SHAKE_TRESHOLD_GRAVITY && lastShakeTimestamp + SHAKE_SLOP_TIME_MS < now) {
            if(lastShakeTimestamp + SHAKE_COUNT_RESET_TIME_MS < now) {
                shakeCount = 0;
            }
            lastShakeTimestamp = now;
            listener.onShake(++shakeCount);
        }
    }

    companion object {
        val SHAKE_TRESHOLD_GRAVITY = 2.7F;
        val SHAKE_SLOP_TIME_MS = 150;
        val SHAKE_COUNT_RESET_TIME_MS = 3000;
    }

}

interface OnShakeListener {
    fun onShake(count: Int);
}

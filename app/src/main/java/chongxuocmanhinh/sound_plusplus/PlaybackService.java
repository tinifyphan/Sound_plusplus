package chongxuocmanhinh.sound_plusplus;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Created by L on 07/11/2016.
 *PlaybackSerivce cho app nghe nhạc
 * Tham khảo thêm MediapLayer android tại(các trạng thái ,các methods call ...)
 * https://developer.android.com/reference/android/media/MediaPlayer.html
 */
public class PlaybackService extends Service
        implements Handler.Callback,
                SongTimeLine.Callback,
                MediaPlayer.OnCompletionListener,/** Hai cái này cần phải implement*/
                MediaPlayer.OnErrorListener
{

    private Looper mLooper;
    private Handler mHandler;
    SoundPlusPLusMediaPlayer mMediaPlayer;
    SoundPlusPLusMediaPlayer mPreparedMediaPlayer;

    AudioManager mAudioManager;

    private Song mCurrentSong;
    SongTimeLine mSongTimeLine;

    /**
     * mảng static tham chiếu đến các playbackActivities, sử dụng cho các hàm callbacks
     */
    private static final ArrayList<TimelineCallback> sCallbacks = new ArrayList<TimelineCallback>(5);

    private String mErrorMessage;

    /**
     * Object được dùng  PlaybackService startup waiting.
     */
    private static final Object[] sWait = new Object[0];

    /**
     * Object dùng để lock các trạng thái khi đang synchronized
     */
    final  Object[] mStateLock = new Object[0];
    /**
     *Nếu được set,thì sẽ play nhạc.
     */
    public static final int FLAG_PLAYING = 0x1;
    /**
     * Được set khi không có media nào trong device.
     */
    public static final int FLAG_NO_MEDIA = 0x2;
    /**
     * Set khi xảy ra lỗi,có thể là bài hát ko mở được.
     */
    public static final int FLAG_ERROR = 0x4;
    /**
     * Được Set khi người dùng cần phải chọn bài hát.
     */
    public static final int FLAG_EMPTY_QUEUE = 0x8;
    public static final int SHIFT_FINISH = 4;
    /**
     * The PlaybackService state, indicating if the service is playing,
     * repeating, etc.
     *
     * The format of this is 0b00000000_00000000_000000gff_feeedcba,
     * where each bit is:
     *     a:   {@link PlaybackService#FLAG_PLAYING}
     *     b:   {@link PlaybackService#FLAG_NO_MEDIA}
     *     c:   {@link PlaybackService#FLAG_ERROR}
     *     d:   {@link PlaybackService#FLAG_EMPTY_QUEUE}
     */
    int mState;

    /**
     * Dùng để sử dụng ở mọi nơi,Single-ton.
     */
    public static PlaybackService sInstance;

    private boolean mMediaPlayerInitialized;
    @Override
    public void onCreate() {
        HandlerThread thread = new HandlerThread("PlaybackService", Process.THREAD_PRIORITY_DEFAULT);
        thread.start();
        Log.d("PlayTest","Created Service");
        mSongTimeLine = new SongTimeLine(this);
        mSongTimeLine.setCallback(this);
        mMediaPlayer = getNewMediaPLayer();
        mPreparedMediaPlayer = getNewMediaPLayer();
        //Ta chỉ sử dụng duy nhất một audioseissionId
        mPreparedMediaPlayer.setAudioSessionId(mMediaPlayer.getAudioSessionId());

        mAudioManager = (AudioManager)getSystemService(AUDIO_SERVICE);

        mLooper = thread.getLooper();
        mHandler = new Handler(mLooper, this);

//        mState !=
        sInstance = this;
        synchronized (sWait) {
            sWait.notifyAll();
        }
    }

    /**
     *Trả về thằng  PlaybackService instance tạo mới nếu chưa có.
     */
    public static PlaybackService get(Context context)
    {
        if (sInstance == null) {
            context.startService(new Intent(context, PlaybackService.class));

            while (sInstance == null) {
                try {
                    synchronized (sWait) {
                        sWait.wait();
                    }
                } catch (InterruptedException ignored) {
                }
            }
        }
        Log.d("TestPlay","Return Service");
        return sInstance;
    }


    public static void addTimelineCallback(TimelineCallback consumer)
    {
        sCallbacks.add(consumer);
    }


    public static void removeTimelineCallback(TimelineCallback consumer)
    {
        sCallbacks.remove(consumer);
    }

    private SoundPlusPLusMediaPlayer getNewMediaPLayer(){
        SoundPlusPLusMediaPlayer mp = new SoundPlusPLusMediaPlayer(this);
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mp.setOnCompletionListener(this);
        mp.setOnErrorListener(this);
        return mp;
    }

    private void prepareMediaPlayer(SoundPlusPLusMediaPlayer mp,String path) throws IOException {
        mp.setDataSource(path);
        mp.prepare();
    }

    private void processSong(Song song){
        try {

            mMediaPlayerInitialized = false;
            mMediaPlayer.reset();
            Log.d("Test","Song path : " + song.path);
            if(mPreparedMediaPlayer.isPlaying()) {
                // The prepared media player is playing as the previous song
                // reched its end 'naturally' (-> gapless)
                // We can now swap mPreparedMediaPlayer and mMediaPlayer
                SoundPlusPLusMediaPlayer tmpPlayer = mMediaPlayer;
                mMediaPlayer = mPreparedMediaPlayer;
                mPreparedMediaPlayer = tmpPlayer; // this was mMediaPlayer and is in reset() state
            }
            else {
                prepareMediaPlayer(mMediaPlayer, song.path);
            }

            mMediaPlayerInitialized = true;

            if ((mState & FLAG_PLAYING) != 0)
                mMediaPlayer.start();

        } catch (IOException e) {
            Log.d("Test","Error!");

        }

        mMediaPlayer.start();
    }

    public void addSongs(QueryTask query){
        Log.d("Testtt","Service Add song");
        mHandler.sendMessage(mHandler.obtainMessage(MSG_QUERY, query));
    }

    /**
     * If playing, pause. If paused, play.
     *
     * @return The new state after this is called.
     */
    public int playPause()
    {
        Log.d("TestPlayPause","Enter service playpause()");
        synchronized (mStateLock) {
            if ((mState & FLAG_PLAYING) != 0)
                return pause();
            else
                return play();
        }
    }

    /**
     * Bắt đầu chơi nhạc nếu đang pause
     * @return
     */
    public int play(){
        Log.d("TestPlayPause","Enter service play()");
        synchronized (mStateLock){
            if((mState & FLAG_EMPTY_QUEUE) != 0){
                setCurrentSong(0);
            }

            int state = updateState(mState | FLAG_PLAYING);
            return state;
        }
    }

    public int pause() {
        Log.d("TestPlayPause","Enter service pause()");
        synchronized (mStateLock) {
            int state = updateState(mState & ~FLAG_PLAYING);
            return state;
        }
    }

    /**
     * Thay đổi state của service
     * @param state
     * @return
     */
    private int updateState(int state){
        if((state & (FLAG_EMPTY_QUEUE|FLAG_ERROR|FLAG_NO_MEDIA)) != 0)
            state &= ~FLAG_PLAYING;

        int oldState = mState;
        mState = state;

        if(state != oldState){
            mHandler.sendMessage(mHandler.obtainMessage(MSG_PROCESS_STATE, oldState, state));
            mHandler.sendMessage(mHandler.obtainMessage(MSG_BROADCAST_CHANGE, state, 0, new TimestampedObject(null)));
        }
        return state;
    }


    private void processNewState(int oldState,int state){
        Log.d("TestPlayPause","processNewState()");
        int toggled = oldState ^ state;
        if( (toggled & FLAG_PLAYING) != 0 && mCurrentSong != null ){
            if((state & FLAG_PLAYING) != 0){//Nếu đang pause và state mới là play
                Log.d("TestPlayPause","mMediaPlayer.start()");
                if (mMediaPlayerInitialized)
                    mMediaPlayer.start();

            }
            else{//Nếu state mới là pause
                Log.d("TestPlayPause"," mMediaPlayer.pause();");
                if (mMediaPlayerInitialized)
                    mMediaPlayer.pause();
            }
        }

    }
    //=========================Handler.Callback=====================================//
    /**
     * Chạy query và add các kết quả vào songtimeline.
     *
     * obj là  QueryTask. arg1 là chế độ add (add mode) (one of SongTimeLine.MODE_*)
     */
    private static final int MSG_QUERY = 2;
    private static final int MSG_BROADCAST_CHANGE = 10;
    private static final int MSG_PROCESS_SONG = 13;
    private static final int MSG_PROCESS_STATE = 14;
    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what){
            case MSG_QUERY:
                runQuery((QueryTask)msg.obj);
                break;
            case MSG_PROCESS_SONG:
                processSong((Song)msg.obj);
                break;
            case MSG_BROADCAST_CHANGE:
                TimestampedObject tso = (TimestampedObject)msg.obj;
                broadcastChange(msg.arg1, (Song)tso.object, tso.uptime);
                break;
            case MSG_PROCESS_STATE:
                processNewState(msg.arg1,msg.arg2);
                break;
            default:
                return false;
        }
        return true;
    }

    /**
     *
     * @param query
     */
    public void runQuery(QueryTask query){
        Log.d("Testtt","Service run query");
        int count = mSongTimeLine.addSongs(this,query);
        int text;
        switch (query.mode) {
            case SongTimeLine.MODE_PLAY:
                text = R.plurals.playing;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    //=======================MediaPlayer.OnCompletionListener=========================//
    @Override
    public void onCompletion(MediaPlayer mp) {

    }
    //=======================MediaPlayer.OnErrorListener=========================//
    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        return false;
    }

    //=======================SongTimeLine.CallBack===============================//
    @Override
    public void activeSongReplaced(int delta, Song song) {
        if (delta == 0)
            setCurrentSong(0);
    }

    @Override
    public void timelineChanged() {
        ArrayList<TimelineCallback> list = sCallbacks;
        for (int i = list.size(); --i != -1; )
            list.get(i).onTimelineChanged();
    }

    @Override
    public void positionInfoChanged() {

    }


    public Song setCurrentSong(int delta){
        if (mMediaPlayer == null)
            return null;

        if (mMediaPlayer.isPlaying())
            mMediaPlayer.stop();

        Song song = mSongTimeLine.getSong(0);
        mCurrentSong = song;
        mHandler.removeMessages(MSG_PROCESS_SONG);

        mMediaPlayerInitialized = false;
        mHandler.sendMessage(mHandler.obtainMessage(MSG_PROCESS_SONG, song));
        mHandler.sendMessage(mHandler.obtainMessage(MSG_BROADCAST_CHANGE, -1, 0, new TimestampedObject(song)));

        return song;
    }

    public Song getSong(int delta){
        if(mSongTimeLine == null)
            return null;
//        if (delta == 0)
//            return mCurrentSong;
        return mSongTimeLine.getSong(delta);
    }


    /**
     * Returns the current position in current song in milliseconds.
     */
    public int getPosition()
    {
        if (!mMediaPlayerInitialized) {
            Log.d("TestPlay","mMediaPlayerInitialized = false");
            return 0;
        }
        return mMediaPlayer.getCurrentPosition();
    }


    /**
     * Seek to a position in the current song.
     *
     * @param progress Proportion of song completed (where 1000 is the end of the song)
     */
    public void seekToProgress(int progress)
    {
        if (!mMediaPlayerInitialized)
            return;
        long position = (long)mMediaPlayer.getDuration() * progress / 1000;
        mMediaPlayer.seekTo((int)position);
    }



    public static boolean hasInstance(){
        return sInstance != null;
    }

    /**
     *
     * @param state
     * @param song
     * @param uptime
     */
    private void broadcastChange(int state, Song song, long uptime){

        if (song != null) {
            ArrayList<TimelineCallback> list = sCallbacks;
            for (int i = list.size(); --i != -1; )
                list.get(i).setSong(uptime, song);
        }
    }

    /**
     * Trả về bài hát tại vị trí trong queue.
     * @param id
     * @return
     */
    public Song getSongByQueuePosition(int id) {
        return mSongTimeLine.getSongByQueuePosition(id);
    }


    public int getTimelinePosition(){
        return mSongTimeLine.getPosition() ;
    }

    public int getTimeLineLength(){
        return mSongTimeLine.getLength();
    }
}

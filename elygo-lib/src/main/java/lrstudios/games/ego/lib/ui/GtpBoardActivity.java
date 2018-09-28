/*
 * This file is part of Elygo-lib.
 * Copyright (C) 2012   Emmanuel Mathis [emmanuel *at* lr-studios.net]
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package lrstudios.games.ego.lib.ui;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.StrictMode;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Hashtable;
import java.util.Locale;

import lrstudios.games.ego.lib.BoardView;
import lrstudios.games.ego.lib.Coords;
import lrstudios.games.ego.lib.EngineContext;
import lrstudios.games.ego.lib.GameInfo;
import lrstudios.games.ego.lib.GameNode;
import lrstudios.games.ego.lib.GoBoard;
import lrstudios.games.ego.lib.GoGame;
import lrstudios.games.ego.lib.GoGameResult;
import lrstudios.games.ego.lib.GtpEngine;
import lrstudios.games.ego.lib.GtpEngineManager;
import lrstudios.games.ego.lib.GtpThread;
import lrstudios.games.ego.lib.IntentGameInfo;
import lrstudios.games.ego.lib.R;
import lrstudios.games.ego.lib.ScoreView;
import lrstudios.games.ego.lib.Utils;
import sapphire.kernel.common.GlobalKernelReferences;
import sapphire.kernel.server.KernelServer;
import sapphire.oms.OMSServer;

public class GtpBoardActivity extends NAActivity implements BoardView.BoardListener, Serializable {
    private transient static final String TAG = "GtpBoardActivity";
    public transient static Context actionContext;

    public transient static final int
            MSG_GTP_MOVE = 2,
            MSG_FINAL_SCORE = 3;

    public transient static final String
            INTENT_SO_TARGET = "sapphire.target",
            INTENT_LOCAL_HOST = "local.host",
            INTENT_PLAY_RESTORE = "lrstudios.games.ego.PLAY_RESTORE",
            INTENT_GTP_BOT_CLASS = "lrstudios.games.ego.BOT_CLASS";

    private transient static GtpThread _gtpThread;

    private transient ScoreView _scoreView;
    private transient ActivityHandler _handler = new ActivityHandler();
    private transient GtpEngine _engine;
    private transient ProgressDialog _waitingScoreDialog;

    private InetSocketAddress getSapphireTarget(boolean isLocal, String localIp)
    {
        OMSServer oms = GlobalKernelReferences.nodeServer.oms;

        ArrayList<InetSocketAddress> kernelServers = null;
        try {
            kernelServers = oms.getServers();
            for (InetSocketAddress sockAddr : kernelServers) {
                if (isLocal){
                    if (sockAddr.getAddress().getHostAddress().equals(localIp)){
                        return sockAddr;
                    }
                } else {
                    if (!sockAddr.getAddress().getHostAddress().equals(localIp)) {
                        return sockAddr;
                    }
                }
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        } catch (NotBoundException e) {
            e.printStackTrace();
        }

        throw new RuntimeException("cannot determine the target node of sapphire model.");
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        actionContext = this;

        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.board_activity);

        _scoreView = (ScoreView) findViewById(R.id.score_view);

        final Bundle extras = getIntent().getExtras();
        IntentGameInfo gameInfo = extras.getParcelable(INTENT_GAME_INFO);
        if (gameInfo == null) {
            showToast(R.string.err_internal);
            finish();
            return;
        }

        // Wait if a previous instance of the bot is still running (this may happen if the user closed
        // this activity during the bot's turn, and reopened it quickly)
        if (_gtpThread != null && _gtpThread.isAlive()) {
            _gtpThread.quit();
            try {
                _gtpThread.join(); // TODO show a ProgressDialog
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        Class<?> botClass = (Class<?>) extras.getSerializable(INTENT_GTP_BOT_CLASS);
        try {
            OMSServer oms = GlobalKernelReferences.nodeServer.oms;

            ArrayList<InetSocketAddress> kernelServers = oms.getServers();
            String soTarget = (String)extras.get(INTENT_SO_TARGET);
            Boolean isLocal = soTarget.equals("local");
            String localIp = (String)extras.get(INTENT_LOCAL_HOST);
            InetSocketAddress target = this.getSapphireTarget(isLocal, localIp);
            Registry registry = LocateRegistry.getRegistry(target.getHostName(), target.getPort());
            KernelServer server = (KernelServer) registry.lookup("SapphireKernelServer");
            Object appEntry = server.startApp("lrstudios.games.ego.lib.DCAPStart");
            GtpEngineManager engineManager = (GtpEngineManager)appEntry;

            _engine = engineManager.getEngine(botClass, new EngineContext());
        }
        catch (Exception e) {
            e.printStackTrace();
            showToast(R.string.err_internal);
            finish();
            return;
        }

        GoGame restoredGame = null;
        int boardSize = gameInfo.boardSize;
        if (extras.getBoolean(INTENT_PLAY_RESTORE, false)) {
            FileInputStream stream = null;
            try {
                stream = openFileInput("gtp_save.sgf");
                restoredGame = GoGame.loadSgf(stream)[0];
                boardSize = restoredGame.info.boardSize;
            }
            catch (Exception e) {
                e.printStackTrace();
                restoredGame = null;
                showToast(R.string.err_cannot_restore_game);
            }
            finally {
                Utils.closeObject(stream);
            }
        }

        // Initialize the engine
        /*
        Properties props = new Properties();
        props.setProperty("level", Integer.toString(gameInfo.botLevel));
        props.setProperty("boardsize", Integer.toString(boardSize));
        */
        Hashtable<String, String> props = new Hashtable<>();
        props.put("level", Integer.toString(gameInfo.botLevel));
        props.put("boardsize", Integer.toString(boardSize));

        if (!_engine.init(props)) {
            showToast(getString(R.string.err_cannot_start_engine, _engine.getName()));
            finish();
            return;
        }
        _engine.setLevel(gameInfo.botLevel);
        _gtpThread = new GtpThread(_engine, _handler, getApplicationContext());
        _gtpThread.start();

        if (restoredGame != null) {
            _engine.newGame(restoredGame);
        }
        else {
            byte color = gameInfo.color;
            if (color == GoBoard.EMPTY)
                color = _random.nextBoolean() ? GoBoard.BLACK : GoBoard.WHITE;

            _engine.newGame(gameInfo.boardSize, color, gameInfo.komi, gameInfo.handicap);
        }

        _boardView.setBoardListener(this);
        _boardView.changeGame(_engine.getGame(), false);

        String botName = _engine.getName();
        String botLevel = getString(R.string.board_bot_level, gameInfo.botLevel);
        String blackName, whiteName, blackRank, whiteRank;
        if (_engine.getPlayerColor() == GoBoard.BLACK) {
            whiteName = botName;
            whiteRank = botLevel;
            blackName = getString(R.string.player);
            blackRank = "";
        }
        else {
            whiteName = getString(R.string.player);
            whiteRank = "";
            blackName = botName;
            blackRank = botLevel;
        }
        _scoreView.setWhiteName(whiteName);
        _scoreView.setWhiteRank(whiteRank);
        _scoreView.setBlackName(blackName);
        _scoreView.setBlackRank(blackRank);
        GameInfo info = _engine.getGame().info;
        info.blackName = blackName;
        info.blackRank = blackRank;
        info.whiteName = whiteName;
        info.whiteRank = whiteRank;

        setTitle(getString(R.string.board_game_vs, botName, botLevel));
        _scoreView.setBlackPrisoners(_engine.getGame().getBlackPrisoners());
        _scoreView.setWhitePrisoners(_engine.getGame().getWhitePrisoners());

        setProgressIndicatorVisibility(false);
        _updateGameLogic();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.actionbar_gtp_board, menu);

        if (_engine.getGame().getCurrentNode().parentNode == null)
            disableOptionItem(R.id.menu_undo);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        super.onOptionsItemSelected(item);
        int id = item.getItemId();
        if (id == R.id.menu_undo) {
            _engine.undo(true);
            _updatePrisoners();
            _updateGameLogic();
        }
        else if (id == R.id.menu_save) {
            // Give a default name to the game : "BotName_YearMonthDay_HoursMinutes"
            Calendar calendar = new GregorianCalendar();
            String defaultName = String.format(Locale.US, "%s_%04d%02d%02d_%02d%02d",
                    _engine.getName().replace(" ", ""),
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH) + 1,
                    calendar.get(Calendar.DAY_OF_MONTH),
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE));

            createFile("application/x-go-sgf", defaultName);
        }
        else if (id == R.id.menu_pass) {
            onPress(-1, -1);
        }
        else if (id == R.id.menu_settings) {
            startActivityForResult(new Intent(this, Preferences.class), BaseBoardActivity.CODE_PREFERENCES_ACTIVITY);
        }
        return true;
    }


    @Override
    public void onPress(int x, int y) {
        if (_engine.playMove(new Coords(x, y))) {
            playStoneSound(x, y);
            _updatePrisoners();
            _updateGameLogic();
        }
        else {
            Log.w(TAG, "The move is illegal : " + x + ", " + y);
        }
    }

    @Override
    public void onCursorMoved(int x, int y) {
    }


    private void createFile(String mimeType, String fileName) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);

        // Filter to only show results that can be "opened", such as
        // a file (as opposed to a list of contacts or timezones).
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        startActivityForResult(intent, 444);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 444) {
            if (resultCode == Activity.RESULT_OK) {
                try {
                    Uri uri = data.getData();
                    ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
                    FileOutputStream outputStream = new FileOutputStream(pfd.getFileDescriptor());
                    outputStream.write(_engine.getGame().getSgf().getBytes());
                    outputStream.close();
                    pfd.close();
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
                Toast.makeText(this, getString(R.string.game_saved, ""), Toast.LENGTH_SHORT).show();
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void _updateGameLogic() {
        GoGame game = _engine.getGame();

        // Enter scoring
        if (game.hasTwoPasses()) {
            _lockPlaying();
            _gtpThread.getFinalScore();
            _waitingScoreDialog = new ProgressDialog(this);
            _waitingScoreDialog.setIndeterminate(true);
            _waitingScoreDialog.setCancelable(true);
            _waitingScoreDialog.setMessage(getString(R.string.board_compute_territory));
            try {
                _waitingScoreDialog.show();
            }
            catch (WindowManager.BadTokenException e) // Happens if the activity is not visible
            {
                e.printStackTrace();
            }
        }
        else if (!game.isFinished()) {
            if (_engine.isBotTurn()) {
                _lockPlaying();
                setProgressIndicatorVisibility(true);
                _gtpThread.playMove();
            }
            else {
                _unlockPlaying();
            }
        }
        setSubtitleMoveNumber(_engine.getGame().getCurrentMoveNumber());
        _boardView.invalidate();
    }


    protected void _lockPlaying() {
        _boardView.lockPlaying();
        disableOptionItem(R.id.menu_undo);
        disableOptionItem(R.id.menu_pass);
    }

    protected void _unlockPlaying() {
        _boardView.unlockPlaying();
        GoGame game = _engine.getGame();
        boolean isFinished = game.getCurrentNode().x >= -1 && !game.isFinished();
        setOptionItemEnabled(R.id.menu_undo, isFinished);
        setOptionItemEnabled(R.id.menu_pass, isFinished);
    }

    protected void _updatePrisoners() {
        GoGame game = _engine.getGame();
        _scoreView.setBlackPrisoners(game.getBlackPrisoners());
        _scoreView.setWhitePrisoners(game.getWhitePrisoners());
        _boardView.addPrisoners(game.getLastPrisoners());
    }


    private class ActivityHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_GTP_MOVE) {
                GoGame game = _engine.getGame();
                GameNode move = game.getCurrentNode();

                if (game.isFinished()) // resign
                {
                    showInfoDialog(getString(R.string.board_player_resigned, _engine.getName()));
                    setTitle(R.string.gtp_resign_win);
                }
                else if (move.x == -1) {
                    // Don't show two dialogs if the game is finished
                    if (!_engine.getGame().hasTwoPasses())
                        showInfoDialog(getString(R.string.board_player_passes, _engine.getName()));
                }
                else if (move.x >= 0) {
                    playStoneSound(move.x, move.y);
                    _updatePrisoners();
                }
                else {
                    Log.e(TAG, "invalid move coordinates : " + move);
                }

                setProgressIndicatorVisibility(false);
                _updateGameLogic();
            }
            else if (msg.what == MSG_FINAL_SCORE) {
                GoGameResult result = (GoGameResult) msg.obj;
                if (_waitingScoreDialog != null) {
                    _waitingScoreDialog.dismiss();
                    _waitingScoreDialog = null;
                }
                _boardView.showFinalStatus(true);
                _boardView.invalidate();

                String winner = getString(result.getWinner() == GoGameResult.BLACK ? R.string.black : R.string.white);
                setTitle(getString(R.string.gtp_game_result,
                        winner, new DecimalFormat("#0.#").format(result.getScore())));
                disableOptionItem(R.id.menu_undo);
                disableOptionItem(R.id.menu_pass);
            }
        }
    }
}

package com.netease.LDNetDiagnoService;

import android.content.Context;
import android.text.TextUtils;

import com.netease.LDNetDiagnoService.LDNetPing.LDNetPingListener;
import com.netease.LDNetDiagnoService.LDNetSocket.LDNetSocketListener;
import com.netease.LDNetDiagnoService.LDNetTraceRoute.LDNetTraceRouteListener;
import com.netease.LDNetDiagnoUtils.LDNetUtil;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网络诊断服务 通过对制定域名进行ping诊断和traceroute诊断收集诊断日志
 *
 * @author panghui
 */
public class LDNetDiagnoService extends
        LDNetAsyncTaskEx<String, String, String> implements LDNetPingListener,
        LDNetTraceRouteListener, LDNetSocketListener {
    private String _appName;
    private String _appVersion;
    private String _UID; // 用户ID
    // private String _dormain; // 接口域名
    private List<String> domainList;

    private boolean _isNetConnected;// 当前是否联网
    private Context _context;
    private String _gateWay;
    private final StringBuilder _logInfo = new StringBuilder(256);
    private LDNetPing _netPinger; // 监控ping命令的执行时间
    private LDNetTraceRoute _traceRouter; // 监控ping模拟traceroute的执行过程
    private boolean _isRunning;

    private LDNetDiagnoListener _netDiagnolistener; // 将监控日志上报到前段页面
    private boolean _isUseJNICTrace = true;

    public LDNetDiagnoService() {
        super();
    }

    /**
     * 初始化网络诊断服务
     *
     * @param theUID
     */
    public LDNetDiagnoService(Context context, String theAppName, String theAppVersion, String theUID,
                              List<String> theDomainList,
                              LDNetDiagnoListener theListener) {
        super();
        this._context = context;
        this._appName = theAppName;
        this._appVersion = theAppVersion;
        this._UID = theUID;
        this.domainList = theDomainList;
        this._netDiagnolistener = theListener;
        //
        this._isRunning = false;
        sExecutor = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE,
                KEEP_ALIVE, TimeUnit.SECONDS, sWorkQueue, sThreadFactory);
        _netPinger = new LDNetPing(this, 4);
        _traceRouter = LDNetTraceRoute.getInstance();
        _traceRouter.initListenter(this);
    }

    @Override
    protected String doInBackground(String... params) {
        if (this.isCancelled())
            return null;
        // TODO Auto-generated method stub
        return this.startNetDiagnosis();
    }

    @Override
    protected void onPostExecute(String result) {
        if (this.isCancelled())
            return;
        super.onPostExecute(result);
        // 线程执行结束
        recordStepInfo("\nEnd of network diagnosis\n");
        this.stopNetDialogsis();
        if (_netDiagnolistener != null) {
            _netDiagnolistener.OnNetDiagnoFinished(_logInfo.toString());
        }
    }

    @Override
    protected void onProgressUpdate(String... values) {
        if (this.isCancelled())
            return;
        // TODO Auto-generated method stub
        super.onProgressUpdate(values);
        if (_netDiagnolistener != null) {
            _netDiagnolistener.OnNetDiagnoUpdated(values[0]);
        }
    }

    @Override
    protected void onCancelled() {
        this.stopNetDialogsis();
    }

    /**
     * 开始诊断网络
     */
    public String startNetDiagnosis() {
        if (domainList != null && !domainList.isEmpty()) {
            this._isRunning = true;
            this._logInfo.setLength(0);
            recordStepInfo("Start Diagnosis...");
            recordCurrentAppVersion();
            recordLocalNetEnvironmentInfo();
            for (String domain : domainList) {
                this.doNetDiagnosis(domain);
            }
            return "End Of Diagnosis";
        } else {
            return "";
        }
    }

    private void doNetDiagnosis(String domain) {
        if (TextUtils.isEmpty(domain)) {
            return;
        }
        if (_isNetConnected) {
            recordStepInfo("\nDiagnostic Domain Name " + domain + "...");
            recordStepInfo("\nStart Ping...");
            _netPinger.exec(domain, false);
            // 开始诊断traceRoute
            recordStepInfo("\nStart Traceroute...");
            _traceRouter.isCTrace = this._isUseJNICTrace;
            _traceRouter.startTraceRoute(domain);
        } else {
            recordStepInfo("\n\nThe current host is not connected to the Internet, please check the network！");
        }
    }

    /**
     * 停止诊断网络
     */
    public String stopNetDialogsis() {
        if (_isRunning) {

            if (_netPinger != null) {
                _netPinger = null;
            }
            if (_traceRouter != null) {
                _traceRouter.resetInstance();
                _traceRouter = null;
            }
            cancel(true);// 尝试去取消线程的执行
            if (sExecutor != null && !sExecutor.isShutdown()) {
                sExecutor.shutdown();
                sExecutor = null;
            }

            _isRunning = false;
        }
        return _logInfo.toString();
    }

    /**
     * 设置是否需要JNICTraceRoute
     *
     * @param use
     */
    public void setIfUseJNICTrace(boolean use) {
        this._isUseJNICTrace = use;
    }

    /**
     * 打印整体loginInfo；
     */
    public void printLogInfo() {
        System.out.print(_logInfo);
    }

    /**
     * 如果调用者实现了stepInfo接口，输出信息
     *
     * @param stepInfo
     */
    private void recordStepInfo(String stepInfo) {
        _logInfo.append(stepInfo + "\n");
        publishProgress(stepInfo + "\n");
    }

    /**
     * traceroute 消息跟踪
     */
    @Override
    public void OnNetTraceFinished() {
    }

    @Override
    public void OnNetTraceUpdated(String log) {
        if (log == null) {
            return;
        }
        if (this._traceRouter != null && this._traceRouter.isCTrace) {
            if (log.contains("ms") || log.contains("***")) {
                log += "\n";
            }
            _logInfo.append(log);
            publishProgress(log);
        } else {
            this.recordStepInfo(log);
        }
    }

    /**
     * socket完成跟踪
     */
    @Override
    public void OnNetSocketFinished(String log) {
        _logInfo.append(log);
        publishProgress(log);
    }

    /**
     * socket更新跟踪
     */
    @Override
    public void OnNetSocketUpdated(String log) {
        _logInfo.append(log);
        publishProgress(log);
    }

    /**
     * 输出关于应用、机器、网络诊断的基本信息
     */
    private void recordCurrentAppVersion() {
        // 输出应用版本信息和用户ID
        recordStepInfo("App Name:\t" + this._appName);
        recordStepInfo("App Version:\t" + this._appVersion);
        recordStepInfo("UserId:\t" + _UID);

        // 输出机器信息
        recordStepInfo("DeviceInfo:\t" + android.os.Build.MANUFACTURER + ":"
                + android.os.Build.BRAND + ":" + android.os.Build.MODEL);
        recordStepInfo("System OS:\t" + android.os.Build.VERSION.RELEASE);
    }

    /**
     * 输出本地网络环境信息
     */
    private void recordLocalNetEnvironmentInfo() {
//        recordStepInfo("\n诊断域名 " + _dormain + "...");
        // 网络状态
        if (LDNetUtil.isNetworkConnected(_context)) {
            _isNetConnected = true;
            recordStepInfo("Current Network:\t" + "Connected");
        } else {
            _isNetConnected = false;
            recordStepInfo("Current Network:\t" + "Not connected");
        }

        // 获取当前网络类型
        String _netType = LDNetUtil.getNetWorkType(_context);
        recordStepInfo("Network Type:\t" + _netType);
        if (_isNetConnected) {
            String _localIp;
            if (LDNetUtil.NETWORKTYPE_WIFI.equals(_netType)) { // wifi：获取本地ip和网关，其他类型：只获取ip
                _localIp = LDNetUtil.getLocalIpByWifi(_context);
                _gateWay = LDNetUtil.pingGateWayInWifi(_context);
            } else {
                _localIp = LDNetUtil.getLocalIpBy3G();
            }
            recordStepInfo("Local IP:\t" + _localIp);
        } else {
            recordStepInfo("Local IP:\t" + "127.0.0.1");
        }
        if (_gateWay != null) {
            recordStepInfo("Local Gateway:\t" + this._gateWay);
        }

        // 获取本地DNS地址
        if (_isNetConnected) {
            String _dns1 = LDNetUtil.getLocalDns("dns1");
            String _dns2 = LDNetUtil.getLocalDns("dns2");
            recordStepInfo("Local DNS:\t" + _dns1 + "," + _dns2);
        } else {
            recordStepInfo("Local DNS:\t" + "0.0.0.0" + "," + "0.0.0.0");
        }

        // 获取远端域名的DNS解析地址
//        if (_isNetConnected) {
//            recordStepInfo("远端域名:\t" + this._dormain);
//            _isDomainParseOk = parseDomain(this._dormain);// 域名解析
//        }
    }

    /**
     * 获取运营商信息
     */
    private String requestOperatorInfo() {
        String res = null;
        String url = LDNetUtil.OPERATOR_URL;
        HttpURLConnection conn = null;
        URL Operator_url;
        try {
            Operator_url = new URL(url);
            conn = (HttpURLConnection) Operator_url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000 * 10);
            conn.connect();
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                res = LDNetUtil.getStringFromStream(conn.getInputStream());
                if (conn != null) {
                    conn.disconnect();
                }
            }
            return res;
        } catch (MalformedURLException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return res;
    }

    /**
     * ping 消息跟踪
     */
    @Override
    public void OnNetPingFinished(String log) {
        this.recordStepInfo(log);
    }

    private static final int CORE_POOL_SIZE = 1;// 4
    private static final int MAXIMUM_POOL_SIZE = 1;// 10
    private static final int KEEP_ALIVE = 10;// 10

    private static final BlockingQueue<Runnable> sWorkQueue = new LinkedBlockingQueue<Runnable>(
            2);// 2
    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "Trace #" + mCount.getAndIncrement());
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }
    };

    private static ThreadPoolExecutor sExecutor = null;

    @Override
    protected ThreadPoolExecutor getThreadPoolExecutor() {
        // TODO Auto-generated method stub
        return sExecutor;
    }

}

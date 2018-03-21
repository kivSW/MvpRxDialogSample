package com.kivsw.cloudcache;

import android.content.Context;

import com.kivsw.cloud.disk.IDiskIO;
import com.kivsw.cloud.disk.IDiskRepresenter;
import com.kivsw.cloud.disk.StorageUtils;
import com.kivsw.cloudcache.data.*;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;

import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

/**
 * This is a file cache
 */

public class CloudCache {

    public static synchronized  CloudCache newInstance(Context context, List<IDiskRepresenter> disks, long maxSize, int maxFileCount) {
            File cacheDir=null;
            cacheDir = context.getExternalCacheDir();
            if(cacheDir==null)
                cacheDir = context.getCacheDir();
            String dir = context.getCacheDir().getAbsolutePath();
            String exDir=  context.getExternalCacheDir().getAbsolutePath();

            return new CloudCache(cacheDir.getAbsolutePath(), disks, maxSize, maxFileCount);
    }


    private List<IDiskRepresenter> disks;
    private String cacheDir;
    private CacheData cacheData=null;
    static private final String mapFileName="map";


    protected CloudCache(String cachePath, List<IDiskRepresenter> disks, long maxSize, int maxFileCount)
    {
        this.disks = disks;
        if(cachePath.charAt(cachePath.length()-1)!=File.separatorChar)
            cachePath = cachePath+File.separatorChar;
        cacheDir = cachePath+CloudCache.class.getName();

        cacheData = new CacheData(cacheDir, mapFileName, 1024*64, 5);

    }

 /*   public Completable markAsLocalyModified(final String filePath)
    {
        CacheFileInfo lastFileInfo=cacheData.map.get(filePath);
        if(lastFileInfo==null) return Completable.error(new Exception("No such file "+filePath));

        lastFileInfo.localyModified = true;

        return Completable.fromAction(new Action() {
            @Override
            public void run() throws Exception {
                doSaveMapData(); // TODO check if it works in an IO-thread
            }
        })
        .observeOn(Schedulers.io());
    };*/

    /**
     * retrieves a file from cache or download the file
     * emits Integer (to indicate download progress) and then emits CacheFileInfo
     * @param fullFilePath
     * @return Observable that emits CacheFileInfo and Integer
     */
    public Observable getFileFromCache(final String fullFilePath)
    {
        // check path correctness
        StorageUtils.CloudFile cf= StorageUtils.parseFileName(fullFilePath, disks);
        if(cf.diskRepresenter==null)
             return Observable.error(new Exception("incorrect path"));

        // check if the file needs to be cached
        final IDiskIO disk=cf.diskRepresenter.getDiskIo();
        final String remoteFilePath=cf.getPath(); // file path without it's scheme
        String localFN=disk.convertToLocalPath(remoteFilePath);
        if(localFN!=null)
            return Observable.just(new CacheFileInfo(localFN, fullFilePath));

        return
                Observable.fromCallable(new Callable<CacheFileInfo>() {
                    @Override
                    public CacheFileInfo call() throws Exception {// gets file from cache
                        CacheFileInfo res=cacheData.get(fullFilePath);
                        if(res==null)
                            res= new CacheFileInfo(); // cache does not have this file
                        return res;
                    }
                })
                .subscribeOn(Schedulers.io())
                .flatMap(new Function<CacheFileInfo, ObservableSource<?>>() {
                    @Override
                    public ObservableSource<?> apply(@NonNull final CacheFileInfo lastCacheFileInfo) throws Exception {

                        if(lastCacheFileInfo.localName==null) return doDownloadFile(fullFilePath); // just download

                        return   disk.getResourceInfo(remoteFilePath) // gets file info
                                .observeOn(Schedulers.io())
                                .flatMapObservable(new Function<IDiskIO.ResourceInfo, ObservableSource<?>>(){ // returns true if we need download file again
                                    @Override
                                    public Observable apply(@NonNull IDiskIO.ResourceInfo resourceInfo) throws Exception {
                                        if(resourceInfo.modified()==lastCacheFileInfo.modifiedTime) { // if server has the same file's time
                                            return Observable.just(lastCacheFileInfo);
                                        }
                                        return doDownloadFile(fullFilePath); //
                                    }
                                })
                                .onErrorResumeNext(new Function<Throwable, ObservableSource<?>>() {
                                    @Override
                                    public Observable apply(@NonNull Throwable throwable) throws Exception {
                                        if(lastCacheFileInfo.localName==null)
                                            return Observable.error(throwable);
                                        lastCacheFileInfo.errorUpdating = throwable;
                                        return Observable.just(lastCacheFileInfo);
                                    }
                                });
                    }
                })
                .observeOn(AndroidSchedulers.mainThread());

    }

    /**
     * downloads a file from remote FS
     * emits Integer (to indicate download progress) and then emits localFilePath
     * @param remoteFilePath
     * @return Observable that emits CacheFileInfo and Integer
     */
    protected Observable doDownloadFile(String remoteFilePath)
    {
        class DownloadParams
        {
            IDiskIO disk;
            String remoteFilePath, cacheFileName;
            StorageUtils.CloudFile cloudFile;
        };
        final DownloadParams params=new DownloadParams();

        params.remoteFilePath = remoteFilePath;
        params.cloudFile = StorageUtils.parseFileName(params.remoteFilePath, disks);
        if(params.cloudFile.diskRepresenter==null)
              return Observable.error(new Exception("incorrect path "+params.remoteFilePath));

        params.disk=params.cloudFile.diskRepresenter.getDiskIo();
        params.cacheFileName = cacheData.generateNewCacheFileName();//cacheDir+"/"+String.valueOf(cacheData.getNewId());

        return
                params.disk.getResourceInfo(params.cloudFile.getPath())
                .observeOn(Schedulers.io())
                .flatMapObservable(new Function<IDiskIO.ResourceInfo, ObservableSource<?>>() { // retrieve file itself
                    @Override
                    public ObservableSource<?> apply(@NonNull final IDiskIO.ResourceInfo resourceInfo) throws Exception {

                        Observable onFinishObservable=Observable.fromCallable(new Callable() {
                                    @Override
                                    public Object call() throws Exception { // updates cache data and return CacheFileInfo
                                        CacheFileInfo cfi=new CacheFileInfo(params.cacheFileName, params.remoteFilePath);
                                        cfi.modifiedTime = resourceInfo.modified();
                                        cacheData.put(params.remoteFilePath, cfi);
                                        return cfi;
                                    }
                                })
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread());

                        return
                            params.disk
                             .downloadFile(params.cloudFile.getPath(), params.cacheFileName)
                             .concatWith(onFinishObservable);
                    }
                });

    };

    /**
     * downloads a file from remote FS
     * emits Integer (to indicate download progress)
     *
     * @param remoteFilePath
     * @return
     */
    public Observable<Integer> uploadFile(final String remoteFilePath) {
        class UploadParams {
            IDiskIO disk;
            CacheFileInfo cfi;
            StorageUtils.CloudFile cloudFile;
        }
        ;
        final UploadParams params = new UploadParams();

        return
                Single.fromCallable(new Callable<UploadParams>() {
                            @Override
                            public UploadParams call() throws Exception {
                                params.cfi = cacheData.get(remoteFilePath); // TODO make sure it's not the main thread
                                if (params.cfi == null)
                                    throw new Exception("no such a file in cache " + remoteFilePath);

                                params.cloudFile = StorageUtils.parseFileName(remoteFilePath, disks);
                                if (params.cloudFile.diskRepresenter == null)
                                    throw new Exception("incorrect path " + remoteFilePath);

                                params.disk = params.cloudFile.diskRepresenter.getDiskIo();
                                return params;
                            }
                        })
                    .subscribeOn(Schedulers.io())
                    .flatMapObservable(new Function<UploadParams, ObservableSource<Integer>>() {
                        @Override
                        public ObservableSource<Integer> apply(@NonNull UploadParams uploadParams) throws Exception {
                            return params.disk.uploadFile(params.cloudFile.getPath(), params.cfi.localName);
                        }
                    })
                    .observeOn(Schedulers.io())
                    .doOnComplete(new Action() {
                        @Override
                        public void run() throws Exception {
                            params.disk.getResourceInfo(params.cloudFile.getPath()) // update file's time
                                    .subscribe(new Consumer<IDiskIO.ResourceInfo>() {
                                        @Override
                                        public void accept(@NonNull IDiskIO.ResourceInfo resourceInfo) throws Exception {
                                            params.cfi.modifiedTime = resourceInfo.modified();
                                            cacheData.put(params.cfi.remoteName, params.cfi);//doSaveCacheMap();
                                        }
                                    });
                        }
                    });
    }



}
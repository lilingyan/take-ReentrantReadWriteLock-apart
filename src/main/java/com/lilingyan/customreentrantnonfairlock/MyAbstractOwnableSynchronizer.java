package com.lilingyan.customreentrantnonfairlock;

/**
 * @link AbstractOwnableSynchronizer
 * @Author: lilingyan
 * @Date 2019/3/9 15:04
 */
public class MyAbstractOwnableSynchronizer {

    /**
     * 当前锁持有线程
     */
    private transient Thread exclusiveOwnerThread;

    protected final void setExclusiveOwnerThread(Thread thread) {
        exclusiveOwnerThread = thread;
    }

    protected final Thread getExclusiveOwnerThread() {
        return exclusiveOwnerThread;
    }

}

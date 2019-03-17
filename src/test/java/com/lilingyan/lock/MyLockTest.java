package com.lilingyan.lock;

import org.junit.Test;

/**
 * @Author: lilingyan
 * @Date 2019/3/9 14:39
 */
@SuppressWarnings("Duplicates")
public class MyLockTest {

    private MyLock lock = new MyLock();

    private class Sequence{

        private int value = 0;

        public int getNext() throws InterruptedException {
            lock.lock();
            Thread.sleep(100);
            value++;
            lock.unlock();
            return value;
        }

    }

    Sequence sequence = new Sequence();

    @Test
    public void increment(){
        new Thread(()->{
            try {
                while (true)
                    System.out.println(sequence.getNext());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
        new Thread(()->{
            try {
                while (true)
                    System.out.println(sequence.getNext());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        while (Thread.activeCount()>0){

        }

    }

}

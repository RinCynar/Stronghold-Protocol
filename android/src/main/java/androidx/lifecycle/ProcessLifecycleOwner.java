package androidx.lifecycle;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Built-in, zero-dependency implementation of ProcessLifecycleOwner for Stronghold Protocol.
 * Directly satisfies Mozilla GeckoView's ProcessLifecycleOwner.get().getLifecycle().addObserver() contract
 * without pulling in heavy Kotlin stdlib or failing AndroidX startup content providers.
 */
public class ProcessLifecycleOwner implements LifecycleOwner {

    private static final String TAG = "ProcessLifecycleOwner";
    private static final ProcessLifecycleOwner sInstance = new ProcessLifecycleOwner();

    public static final ProcessLifecycleOwner newInstance = sInstance;

    public static LifecycleOwner get() {
        return sInstance;
    }

    public static void init(android.content.Context context) {
        // No-op, built-in singleton ready immediately
    }

    private final SimpleLifecycle mLifecycle = new SimpleLifecycle(this);

    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    public static void onAppCreate() {
        sInstance.mLifecycle.dispatch(Lifecycle.Event.ON_CREATE);
    }

    public static void onAppStart() {
        sInstance.mLifecycle.dispatch(Lifecycle.Event.ON_START);
    }

    public static void onAppResume() {
        sInstance.mLifecycle.dispatch(Lifecycle.Event.ON_RESUME);
    }

    public static void onAppPause() {
        sInstance.mLifecycle.dispatch(Lifecycle.Event.ON_PAUSE);
    }

    public static void onAppStop() {
        sInstance.mLifecycle.dispatch(Lifecycle.Event.ON_STOP);
    }

    public static void onAppDestroy() {
        sInstance.mLifecycle.dispatch(Lifecycle.Event.ON_DESTROY);
    }

    private static class SimpleLifecycle extends Lifecycle {
        private final LifecycleOwner mOwner;
        private final CopyOnWriteArrayList<LifecycleObserver> mObservers = new CopyOnWriteArrayList<LifecycleObserver>();
        private Lifecycle.State mCurrentState = Lifecycle.State.INITIALIZED;
        private final Handler mMainHandler = new Handler(Looper.getMainLooper());

        SimpleLifecycle(LifecycleOwner owner) {
            this.mOwner = owner;
        }

        @Override
        public void addObserver(final LifecycleObserver observer) {
            if (observer == null) return;
            mObservers.add(observer);

            // Bring the newly added observer up to speed with the active lifecycle
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    dispatchToObserver(observer, Lifecycle.Event.ON_CREATE);
                    dispatchToObserver(observer, Lifecycle.Event.ON_START);
                    dispatchToObserver(observer, Lifecycle.Event.ON_RESUME);
                }
            });
        }

        @Override
        public void removeObserver(LifecycleObserver observer) {
            if (observer != null) {
                mObservers.remove(observer);
            }
        }

        @Override
        public Lifecycle.State getCurrentState() {
            return mCurrentState;
        }

        void dispatch(final Lifecycle.Event event) {
            updateState(event);
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    for (LifecycleObserver observer : mObservers) {
                        dispatchToObserver(observer, event);
                    }
                }
            });
        }

        private void updateState(Lifecycle.Event event) {
            switch (event) {
                case ON_CREATE:
                case ON_STOP:
                    mCurrentState = Lifecycle.State.CREATED;
                    break;
                case ON_START:
                case ON_PAUSE:
                    mCurrentState = Lifecycle.State.STARTED;
                    break;
                case ON_RESUME:
                    mCurrentState = Lifecycle.State.RESUMED;
                    break;
                case ON_DESTROY:
                    mCurrentState = Lifecycle.State.DESTROYED;
                    break;
                default:
                    break;
            }
        }

        private void dispatchToObserver(LifecycleObserver observer, Lifecycle.Event event) {
            try {
                for (Method m : observer.getClass().getDeclaredMethods()) {
                    boolean match = false;
                    OnLifecycleEvent ann = m.getAnnotation(OnLifecycleEvent.class);
                    if (ann != null && ann.value() == event) {
                        match = true;
                    } else if (ann == null) {
                        String name = m.getName().toLowerCase();
                        String eventName = event.name().toLowerCase().replace("on_", "");
                        if (name.equals("on" + eventName)) {
                            match = true;
                        }
                    }
                    if (match) {
                        m.setAccessible(true);
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length == 0) {
                            m.invoke(observer);
                        } else if (params.length == 1 && params[0].isAssignableFrom(LifecycleOwner.class)) {
                            m.invoke(observer, mOwner);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Error dispatching lifecycle event " + event + " to " + observer, t);
            }
        }
    }
}

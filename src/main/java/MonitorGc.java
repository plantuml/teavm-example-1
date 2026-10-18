/**
 * Holds a monitor ("synchronized") while allocating a lot, so that the garbage collector runs
 * while the monitor is held, then releases it.
 *
 * Usage: MonitorGc [rounds]   (default: 20 rounds)
 */
public final class MonitorGc {

	private static final Object LOCK = new Object();
	private static final Object[] SINK = new Object[64];

	/** Same shape as a lazy holder: the initialisation allocates, under the monitor of 'this'. */
	private static final class Holder {
		private Object value;

		synchronized Object get() {
			if (value == null) {
				allocateALot();
				value = new Object();
			}
			return value;
		}
	}

	private static void allocateALot() {
		for (int i = 0; i < 100000; i++)
			SINK[i & 63] = new byte[100 + i % 400];
	}

	public static void main(String[] args) {
		final int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 20;

		for (int round = 1; round <= rounds; round++) {
			synchronized (LOCK) {
				allocateALot();
			}
			System.out.println("round " + round + ": synchronized block ok");
		}

		for (int round = 1; round <= rounds; round++) {
			new Holder().get();
			System.out.println("round " + round + ": synchronized method ok");
		}
		System.out.println("MonitorGc: done");
	}
}

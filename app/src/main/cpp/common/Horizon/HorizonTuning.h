// SPDX-FileCopyrightText: 2002-2026 PCSX2 Dev Team
// SPDX-License-Identifier: GPL-3.0+

#pragma once

#include "common/Pcsx2Defs.h"

#include <atomic>
#include <cstddef>

// Switch-specific runtime tuning and diagnostics.
//
// Everything here is optional: each tweak has a toggle (read from the [Horizon] section of
// armsx2.ini by the host application) so a regression can be ruled out on the console without
// rebuilding.
namespace Horizon
{
	// --------------------------------------------------------------------------------------
	//  Thread placement
	// --------------------------------------------------------------------------------------
	// Background:
	//  * hbloader gives homebrew cores 0-2.
	//  * libnx creates every pthread/std::thread at priority 59 (the lowest) and lets it float
	//    across all allowed cores (threadCreate() followed by svcSetThreadCoreMask(-1, mask)).
	//  * The EE runs on the main thread at priority 44.
	// So the GS and VU threads start at the same (lowest) priority as every helper thread (disc
	// reader, audio, shader compiler, input...) and time-slice against them.
	//
	// With thread tuning enabled the GS/VU threads are raised to the EE thread's priority, the
	// audio thread goes one step above it, and helpers prefer core 1.
	//
	// Measured on a Switch V1 (fabrica-7 log): threads were already spread over cores 0-2 before
	// this existed, so the only real change is the priority of the GS/VU/audio threads.

	/// Captures the calling (main/EE) thread's priority and stores the toggle.
	/// Must be called once from the main thread before any other thread is created.
	void InitThreadTuning(bool enabled);

	bool IsThreadTuningEnabled();

	/// Applies the placement policy for the calling thread, selected by its name.
	/// Called from Threading::SetNameOfCurrentThread().
	void ApplyThreadPolicy(const char* name);

	// --------------------------------------------------------------------------------------
	//  EE code-page read faults
	// --------------------------------------------------------------------------------------
	// Horizon cannot make a fastmem alias read-only, so a write-protected code page is unmapped
	// from the fastmem arena and *reads* of it fault as well. When enabled, such a read is routed
	// through the slow path (backpatch) instead of being treated as a write to code.
	void SetCodePageReadBackpatchEnabled(bool enabled);
	bool IsCodePageReadBackpatchEnabled();

	// --------------------------------------------------------------------------------------
	//  Diagnostics
	// --------------------------------------------------------------------------------------
	struct FaultStats
	{
		std::atomic<u64> lazy_resolved{0}; ///< fastmem faults resolved by mapping pages in
		std::atomic<u64> lazy_pages{0}; ///< pages mapped by those faults
		std::atomic<u64> limit_refusals{0}; ///< faults refused because the live page limit was hit
		std::atomic<u64> protected_reads{0}; ///< reads that faulted on a write-protected code page
		std::atomic<u64> protected_read_backpatches{0}; ///< ...of which were backpatched
		std::atomic<u64> protected_writes{0}; ///< writes that faulted on a write-protected code page
		std::atomic<u64> backpatches{0}; ///< ordinary (non code page) backpatches
		std::atomic<u64> unhandled{0}; ///< faults nothing could handle
		std::atomic<u32> live_pages{0}; ///< fastmem alias pages currently mapped
		std::atomic<u32> live_page_limit{0};
	};

	FaultStats& GetFaultStats();

	/// Counters for HostSys::MemProtect()'s fallback path (see HorizonHostSys.cpp).
	struct ReprotectStats
	{
		std::atomic<u64> split_calls{0}; ///< bulk calls the kernel refused and that were redone piecewise
		std::atomic<u64> split_runs{0}; ///< uniform runs reprotected by those calls
		std::atomic<u64> failures{0}; ///< runs (or whole calls) that could not be reprotected
	};

	ReprotectStats& GetReprotectStats();

	// --------------------------------------------------------------------------------------
	//  Thread registry
	// --------------------------------------------------------------------------------------
	// Every long-lived thread announces itself through Threading::SetNameOfCurrentThread(). The
	// registry remembers its kernel handle so diagnostics (the sampling profiler) can find the
	// emulation threads by name.
	struct ThreadRecord
	{
		char name[32];
		u32 handle; ///< kernel handle, valid while the thread is alive
		u64 thread_id; ///< kernel thread id, to tell a live thread from a reused handle
		u64 stack_begin; ///< the thread's stack as libnx knows it (0 if unknown)
		u64 stack_end;
	};

	/// Registers (or re-registers) the calling thread under the given name.
	void RegisterCurrentThread(const char* name);

	/// Copies the registered threads into out and returns how many were written.
	size_t GetRegisteredThreads(ThreadRecord* out, size_t max_count);
} // namespace Horizon

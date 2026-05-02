import { configureStore, createSlice } from '@reduxjs/toolkit';

// UI State Slice
const uiSlice = createSlice({
  name: 'ui',
  initialState: {
    sidebarOpen: true,
    selectedClusterId: null,
  },
  reducers: {
    toggleSidebar: (state) => {
      state.sidebarOpen = !state.sidebarOpen;
    },
    setSidebarOpen: (state, action) => {
      state.sidebarOpen = action.payload;
    },
    setSelectedClusterId: (state, action) => {
      state.selectedClusterId = action.payload;
    },
  },
});

// Clusters Slice
const clustersSlice = createSlice({
  name: 'clusters',
  initialState: {
    items: [],
    loading: false,
    error: null,
  },
  reducers: {
    setClusters: (state, action) => {
      state.items = action.payload;
    },
    setLoading: (state, action) => {
      state.loading = action.payload;
    },
    setError: (state, action) => {
      state.error = action.payload;
    },
    addCluster: (state, action) => {
      state.items.push(action.payload);
    },
    removeCluster: (state, action) => {
      state.items = state.items.filter(c => c.id !== action.payload);
    },
    updateCluster: (state, action) => {
      const idx = state.items.findIndex(c => c.id === action.payload.id);
      if (idx !== -1) {
        state.items[idx] = { ...state.items[idx], ...action.payload };
      }
    },
  },
});

export const store = configureStore({
  reducer: {
    ui: uiSlice.reducer,
    clusters: clustersSlice.reducer,
  },
});

export const uiActions = uiSlice.actions;
export const clustersActions = clustersSlice.actions;

export default store;


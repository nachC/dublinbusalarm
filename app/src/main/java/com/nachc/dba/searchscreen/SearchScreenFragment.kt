package com.nachc.dba.searchscreen

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.*
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import com.google.android.material.tabs.TabLayout
import com.nachc.dba.R
import com.nachc.dba.databinding.SearchScreenFragmentBinding
import com.nachc.dba.models.Trip
import com.nachc.dba.ui.MainScreenFragmentDirections
import java.util.*

class SearchScreenFragment : Fragment() {

    val TAG = "SearchScreenFragment"
    val FINE_LOCATION = 1 // request code for fine location access permission

    private var isConnected: Boolean = false // flag for internet connectivity

    private val viewModel: SearchScreenViewModel by viewModels()
    private lateinit var binding: SearchScreenFragmentBinding

    // Observer to be aware of loading process
    private val loadingObserver = Observer<Boolean> { isLoading ->
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        if (isLoading) {
            binding.loadError.visibility = View.GONE
        }
    }
    // Observer to be aware of any error from the backend
    private val errorObserver = Observer<Pair<Boolean, String>> { isError ->
        if (isError.first) {
            binding.loadError.text = isError.second
            binding.loadError.visibility = View.VISIBLE
        } else {
            View.GONE
        }
    }
    // Observer to be aware of input validation results
    // as this is the first thing to check, we'll hide the keyboard here
    private val validInputObserver = Observer<Pair<Boolean, String>> { isValidInput ->
        val imm = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.inputLineEditText.windowToken, 0)
        if (!isValidInput.first) {
            // input is not valid, show respective error
            binding.inputLineEditText.error = isValidInput.second
        }
    }
    // Observer to be aware of the trips being set
    private val tripsObserver = Observer<List<Trip>> { trips ->
        // trips will be set to null on onViewCreated because we could be coming back from routelist fragment
        if (trips != null) {
            findNavController().navigate(
                MainScreenFragmentDirections.actionMainScreenToRouteList(
                    trips.toTypedArray()
                )
            )
        }
    }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DataBindingUtil.inflate(
            inflater, R.layout.search_screen_fragment, container, false
        )

        // Set the ViewModel for databinding - this allows the bound layout access to all of the
        // data in the VieWModel
        binding.searchScreenViewModel = viewModel

        // Specify the current activity as the lifecycle owner of the binding. This is used so that
        // the binding can observe LiveData updates
        binding.lifecycleOwner = this

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // check if we have location permissions
        if (checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            binding.searchBtn.isEnabled = true
        } else {
            // request location permissions
            binding.searchBtn.isEnabled = false
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), FINE_LOCATION)
        }

        // Check for network connectivity. We'll allow the search functionality only if a connection is available
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.i(TAG, "Network onAvailable")
                isConnected = true
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.i(TAG, "Network onLost")
                isConnected = false
            }
        })

        // set trips to null in case we come from the routeList fragment by pressing the back button
        viewModel.resetTrips()

        binding.permissionBtn.setOnClickListener {
            openPermissionSettings()
        }
        binding.allowLocationBtn.setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), FINE_LOCATION)
        }
        binding.dataSavedTextView.setOnClickListener {
            showDataSavedDialog()
        }
        binding.searchBtn.setOnClickListener {
            if (!isConnected) {
                Toast.makeText(
                    context,
                    "Please, check your internet connection",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                viewModel.search(binding.inputLineEditText.text.toString().toLowerCase(Locale.ROOT))
            }
        }

        // start observing the ViewModel
        viewModel.validInput.observe(viewLifecycleOwner, validInputObserver)
        viewModel.loading.observe(viewLifecycleOwner, loadingObserver)
        viewModel.loadError.observe(viewLifecycleOwner, errorObserver)
        viewModel.trips.observe(viewLifecycleOwner, tripsObserver)
    }

    // here we handle the result of calling requestPermissions
    // if user provided permission for FINE LOCATION we proceed to call search on the viewModel
    // if user denies, we show Toast with info
    // if user denies and checks "don't ask again", we show a settings button to redirect user to permissions settings
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == FINE_LOCATION) {
            // received request permission for fine-location
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // we have permission to use location -> we can search now
                binding.searchBtn.isEnabled = true
                binding.allowLocationBtn.visibility = View.GONE
            } else {
                // we don't have location permission
                Toast.makeText(context, "Permission was not granted", Toast.LENGTH_SHORT).show()
                // see if user checked "Don't ask again" box before, if so, show button to open OS Settings
                if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    Log.i(TAG, "redirect user to settings")
                    binding.allowLocationBtn.visibility = View.GONE
                    binding.permissionBtn.visibility = View.VISIBLE
                } else {
                    binding.permissionBtn.visibility = View.GONE
                    binding.allowLocationBtn.visibility = View.VISIBLE
                }
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    // method called when clicking the permission button
    // we send the user to the OS settings screen to set location permission manually
    fun openPermissionSettings() {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            val uri = Uri.fromParts("package", activity?.packageName, null)
            intent.data = uri
            // we'll handle the result on onActivityResult
            startActivityForResult(intent, 0)
    }

    // if we come from the SETTINGS screen and user provided location permission
    // then we can search
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 0) {
            binding.permissionBtn.visibility = View.INVISIBLE
            binding.searchBtn.isEnabled = true
            Toast.makeText(this.context, "You can search now", Toast.LENGTH_SHORT).show()
        }
    }

    // method to show an AlertDialog with information
    // regarding what data is saved to the DB
    fun showDataSavedDialog() {
        AlertDialog.Builder(activity)
            .setTitle("Data saved")
            .setMessage(
                """ 
            Four things are saved to the database: 
            - The coordinates of the stop you selected. 
            - Your coordinates at the moment you select a stop. 
            - The time it took for the bus to reach the selected stop. 
            - The date the trip was made.
            No data is saved that could identify you or your device.
            The goal is to gain insights in what routes and stops are frequently used, and the time it takes for the given trips. 
            """.trimIndent()
            ) // A null listener allows the button to dismiss the dialog and take no further action.
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}